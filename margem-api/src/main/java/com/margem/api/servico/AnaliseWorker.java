package com.margem.api.servico;

import com.margem.api.dominio.Documento;
import com.margem.api.dominio.Enums.Categoria;
import com.margem.api.dominio.Enums.Severidade;
import com.margem.api.dominio.NotasCriterios;
import com.margem.api.dominio.Orientacao;
import com.margem.api.repositorio.DocumentoRepository;
import com.margem.api.servico.extracao.DocumentoExtraido;
import com.margem.api.servico.extracao.ExtratorTexto;
import com.margem.api.servico.ia.AnaliseIaService;
import com.margem.api.servico.ia.OpenAiClient.FalhaIaException;
import com.margem.api.servico.ia.RespostaAnalise;
import com.margem.api.servico.ia.RespostaAnalise.OrientacaoIa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Roda a análise fora da requisição HTTP (pode levar de 30 s a alguns minutos).
 * O front acompanha consultando GET /api/documentos/{id} até status CONCLUIDO ou ERRO.
 * A chamada à IA fica fora de transação para não segurar conexão do banco.
 */
@Component
public class AnaliseWorker {

    private static final Logger log = LoggerFactory.getLogger(AnaliseWorker.class);

    private final DocumentoRepository repo;
    private final ExtratorTexto extrator;
    private final AnaliseIaService ia;
    private final Armazenamento armazenamento;
    private final TransactionTemplate tx;

    public AnaliseWorker(DocumentoRepository repo, ExtratorTexto extrator, AnaliseIaService ia,
                         Armazenamento armazenamento, TransactionTemplate tx) {
        this.repo = repo;
        this.extrator = extrator;
        this.ia = ia;
        this.armazenamento = armazenamento;
        this.tx = tx;
    }

    @Async
    public void processar(UUID documentoId) {
        Documento doc = tx.execute(s -> {
            Documento d = repo.findById(documentoId).orElseThrow();
            d.iniciarAnalise();
            return d;
        });
        try {
            DocumentoExtraido extraido = extrator.extrair(armazenamento.caminho(doc.getCaminhoArquivo()), doc.getFormato());
            RespostaAnalise resposta = ia.analisar(extraido, doc.getTipoTrabalho(), doc.getCurso());
            List<Orientacao> orientacoes = converter(resposta.orientacoes(), extraido);
            var n = resposta.notas();
            tx.executeWithoutResult(s -> repo.findById(documentoId).ifPresent(d ->
                    d.concluirAnalise(
                            new NotasCriterios(n.estrutura(), n.argumentacao(), n.clareza(), n.citacoesReferencias(), n.formatacaoAbnt()),
                            resposta.resumo(), orientacoes, extraido.totalPaginas())));
            log.info("Documento {} analisado: {} orientações", documentoId, orientacoes.size());
        } catch (Exception e) {
            log.error("Falha ao analisar documento {}", documentoId, e);
            String mensagem = mensagemParaAluno(e);
            tx.executeWithoutResult(s -> repo.findById(documentoId).ifPresent(d -> d.falhar(mensagem)));
        }
    }

    static List<Orientacao> converter(List<OrientacaoIa> lista, DocumentoExtraido extraido) {
        List<Orientacao> resultado = new ArrayList<>();
        int ordem = 0;
        for (OrientacaoIa o : lista) {
            Integer indice = extraido.localizar(o.pagina(), o.paragrafo())
                    .or(() -> extraido.localizarPorTrecho(o.trecho()))
                    .map(DocumentoExtraido.Paragrafo::indice)
                    .orElse(null);
            resultado.add(new Orientacao(
                    ordem++,
                    enumOu(Severidade.class, o.severidade(), Severidade.ATENCAO),
                    enumOu(Categoria.class, o.categoria(), Categoria.CLAREZA),
                    cortar(o.titulo(), 300),
                    o.pagina(), o.paragrafo(), indice,
                    cortar(o.trecho(), 2000),
                    cortar(o.porQueImporta(), 2000),
                    cortar(o.comoAjustar(), 2000),
                    o.correcao() == null ? null : cortar(o.correcao().original(), 1000),
                    o.correcao() == null ? null : cortar(o.correcao().sugerido(), 1000)));
        }
        return resultado;
    }

    private static <E extends Enum<E>> E enumOu(Class<E> tipo, String valor, E padrao) {
        try {
            return Enum.valueOf(tipo, valor);
        } catch (Exception e) {
            return padrao;
        }
    }

    private static String cortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String mensagemParaAluno(Exception e) {
        if (e instanceof FalhaIaException f) {
            return f.isTemporaria()
                    ? "A análise não pôde ser concluída agora. Tente de novo em alguns minutos."
                    : "Não foi possível analisar este documento.";
        }
        if (e instanceof java.io.IOException || e instanceof org.apache.poi.EmptyFileException) {
            return e.getMessage() != null && e.getMessage().contains("escaneada")
                    ? e.getMessage()
                    : "Não conseguimos ler o arquivo. Confira se ele abre normalmente no Word.";
        }
        if (e instanceof IllegalArgumentException) return e.getMessage();
        return "Algo deu errado na análise. Tente enviar de novo.";
    }
}
