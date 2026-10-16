package com.margem.api.servico;

import com.margem.api.dominio.*;
import com.margem.api.dominio.Enums.Formato;
import com.margem.api.dominio.Enums.StatusDocumento;
import com.margem.api.dominio.Enums.TipoTrabalho;
import com.margem.api.repositorio.DocumentoRepository;
import com.margem.api.servico.abnt.FormatadorAbnt;
import com.margem.api.servico.extracao.DocumentoExtraido;
import com.margem.api.servico.extracao.ExtratorTexto;
import com.margem.api.web.dto.Mapeador;
import com.margem.api.web.dto.Respostas.DocumentoDetalhe;
import com.margem.api.web.dto.Respostas.DocumentoResumo;
import com.margem.api.web.dto.Respostas.OrientacaoDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentoService {

    private static final Logger log = LoggerFactory.getLogger(DocumentoService.class);
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final DocumentoRepository repo;
    private final Armazenamento armazenamento;
    private final AnaliseWorker worker;
    private final FormatadorAbnt formatador;
    private final ExtratorTexto extrator;

    public DocumentoService(DocumentoRepository repo, Armazenamento armazenamento, AnaliseWorker worker,
                            FormatadorAbnt formatador, ExtratorTexto extrator) {
        this.repo = repo;
        this.armazenamento = armazenamento;
        this.worker = worker;
        this.formatador = formatador;
        this.extrator = extrator;
    }

    /** Recebe o arquivo, guarda e dispara a análise em segundo plano. */
    public DocumentoDetalhe criar(String clienteId, MultipartFile arquivo, TipoTrabalho tipo,
                                  String curso, EscalaNota escala) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) throw new Erros.Invalido("Escolha um arquivo para enviar.");
        if (arquivo.getSize() > MAX_BYTES) throw new Erros.Invalido("O arquivo passa de 20 MB.");

        String nome = nomeSeguro(arquivo.getOriginalFilename());
        Formato formato = detectarFormato(nome, arquivo);

        String cursoLimpo = curso == null || curso.isBlank() ? null
                : curso.trim().substring(0, Math.min(150, curso.trim().length()));
        Documento doc = new Documento(clienteId, nome, formato, tipo, cursoLimpo, escala, null, arquivo.getSize());
        try (InputStream in = arquivo.getInputStream()) {
            doc.definirArquivo(armazenamento.salvar(doc.getId(), "original." + formato.name().toLowerCase(Locale.ROOT), in));
        }
        repo.save(doc); // sem @Transactional: já está gravado quando o worker começa
        worker.processar(doc.getId()); // assíncrono: volta na hora
        return Mapeador.detalhe(doc);
    }

    @Transactional(readOnly = true)
    public List<DocumentoResumo> listar(String clienteId, int limite) {
        return repo.findByClienteIdOrderByCriadoEmDesc(clienteId, PageRequest.of(0, Math.min(Math.max(limite, 1), 100)))
                .stream().map(Mapeador::resumo).toList();
    }

    @Transactional(readOnly = true)
    public DocumentoDetalhe detalhe(String clienteId, UUID id) {
        return Mapeador.detalhe(buscarCompleto(clienteId, id));
    }

    @Transactional
    public DocumentoDetalhe atualizarEscala(String clienteId, UUID id, EscalaNota escala) {
        Documento d = buscarCompleto(clienteId, id);
        d.setEscalaNota(escala);
        return Mapeador.detalhe(d);
    }

    @Transactional
    public OrientacaoDto marcarOrientacao(String clienteId, UUID id, UUID orientacaoId, boolean resolvida) {
        Documento d = buscarCompleto(clienteId, id);
        Orientacao o = d.getOrientacoes().stream().filter(x -> x.getId().equals(orientacaoId)).findFirst()
                .orElseThrow(() -> new Erros.NaoEncontrado("Orientação não encontrada."));
        o.setResolvida(resolvida);
        return Mapeador.orientacao(o);
    }

    /** Sem @Transactional de propósito: o status precisa estar gravado antes do worker começar. */
    public void reanalisar(String clienteId, UUID id) {
        Documento d = buscar(clienteId, id);
        if (d.getStatus() == StatusDocumento.ANALISANDO) throw new Erros.Conflito("A análise já está em andamento.");
        d.iniciarAnalise();
        repo.save(d);
        worker.processar(d.getId());
    }

    /** Gera a cópia ABNT (.docx). Leva poucos segundos, então é síncrono. */
    @Transactional
    public DocumentoDetalhe formatar(String clienteId, UUID id, boolean aplicarCorrecoes) throws IOException {
        Documento d = buscarCompleto(clienteId, id);
        if (d.getFormato() != Formato.DOCX) {
            throw new Erros.Conflito("Este trabalho foi enviado em PDF. Para formatar em ABNT, envie o arquivo .docx.");
        }
        if (d.getStatus() != StatusDocumento.CONCLUIDO) {
            throw new Erros.Conflito("A formatação fica disponível depois da análise.");
        }

        List<FormatadorAbnt.Correcao> correcoes = aplicarCorrecoes
                ? d.getOrientacoes().stream()
                    .filter(o -> o.getCorrecaoOriginal() != null && o.getCorrecaoSugerida() != null)
                    .map(o -> new FormatadorAbnt.Correcao(o.getParagrafoIndice(), o.getCorrecaoOriginal(), o.getCorrecaoSugerida()))
                    .toList()
                : List.of();

        Path original = armazenamento.caminho(d.getCaminhoArquivo());
        FormatadorAbnt.Resultado r;
        try (InputStream in = Files.newInputStream(original)) {
            r = formatador.formatar(in, correcoes);
        }

        // página de cada parágrafo, para o front mostrar onde a formatação mudou
        Map<Integer, Integer> paginaPorIndice;
        try (InputStream in = Files.newInputStream(original)) {
            paginaPorIndice = extrator.extrairDocx(in).paragrafos().stream()
                    .collect(Collectors.toMap(DocumentoExtraido.Paragrafo::indice, DocumentoExtraido.Paragrafo::pagina, (a, b) -> a));
        }

        armazenamento.apagar(d.getCaminhoFormatado());
        String caminho = armazenamento.salvar(d.getId(), "abnt.docx", r.arquivo());
        d.registrarFormatacao(caminho,
                r.alteracoes().stream()
                        .map(a -> new AlteracaoFormatacao(a.paragrafoIndice(), paginaPorIndice.get(a.paragrafoIndice()),
                                a.trecho(), a.descricao()))
                        .toList(),
                r.correcoesAplicadas());
        log.info("Documento {} formatado: {} alterações, {} correções aplicadas, {} ignoradas",
                id, r.alteracoes().size(), r.correcoesAplicadas(), r.correcoesIgnoradas());
        return Mapeador.detalhe(d);
    }

    public record ArquivoFormatado(Path caminho, String nome) {
    }

    @Transactional(readOnly = true)
    public ArquivoFormatado arquivoFormatado(String clienteId, UUID id) {
        Documento d = buscar(clienteId, id);
        if (d.getCaminhoFormatado() == null) throw new Erros.NaoEncontrado("A cópia formatada ainda não foi gerada.");
        String base = d.getNomeArquivo().replaceFirst("(?i)\\.docx$", "");
        return new ArquivoFormatado(armazenamento.caminho(d.getCaminhoFormatado()), base + " (ABNT).docx");
    }

    @Transactional
    public void apagar(String clienteId, UUID id) {
        Documento d = buscar(clienteId, id);
        armazenamento.apagar(d.getCaminhoArquivo());
        armazenamento.apagar(d.getCaminhoFormatado());
        repo.delete(d);
    }

    /** Análises interrompidas por reinício do servidor voltam para a fila. */
    @EventListener(ApplicationReadyEvent.class)
    public void retomarPendentes() {
        repo.findByStatusIn(List.of(StatusDocumento.RECEBIDO, StatusDocumento.ANALISANDO)).forEach(d -> {
            log.info("Retomando análise do documento {}", d.getId());
            worker.processar(d.getId());
        });
    }

    // ---------------------------------------------------------------- apoio

    private Documento buscar(String clienteId, UUID id) {
        return repo.findByIdAndClienteId(id, clienteId)
                .orElseThrow(() -> new Erros.NaoEncontrado("Trabalho não encontrado."));
    }

    private Documento buscarCompleto(String clienteId, UUID id) {
        return repo.findComOrientacoesByIdAndClienteId(id, clienteId)
                .orElseThrow(() -> new Erros.NaoEncontrado("Trabalho não encontrado."));
    }

    static String nomeSeguro(String nome) {
        if (nome == null || nome.isBlank()) return "trabalho";
        String limpo = Path.of(nome.replace('\\', '/')).getFileName().toString()
                .replaceAll("[\\p{Cntrl}<>:\"/|?*]", "_").trim();
        return limpo.length() > 200 ? limpo.substring(limpo.length() - 200) : limpo;
    }

    /** Confere extensão e assinatura do arquivo (não confia só no nome). */
    static Formato detectarFormato(String nome, MultipartFile arquivo) throws IOException {
        String minusculo = nome.toLowerCase(Locale.ROOT);
        byte[] cabeca = new byte[4];
        try (InputStream in = arquivo.getInputStream()) {
            int lidos = in.readNBytes(cabeca, 0, 4);
            if (lidos < 4) throw new Erros.Invalido("O arquivo está vazio ou corrompido.");
        }
        boolean zip = cabeca[0] == 'P' && cabeca[1] == 'K';
        boolean pdf = cabeca[0] == '%' && cabeca[1] == 'P' && cabeca[2] == 'D' && cabeca[3] == 'F';
        if (minusculo.endsWith(".docx") && zip) return Formato.DOCX;
        if (minusculo.endsWith(".pdf") && pdf) return Formato.PDF;
        if (minusculo.endsWith(".doc")) {
            throw new Erros.Invalido("Arquivos .doc antigos não são aceitos. No Word, use Salvar como > .docx.");
        }
        throw new Erros.Invalido("Envie um arquivo .docx ou .pdf.");
    }
}
