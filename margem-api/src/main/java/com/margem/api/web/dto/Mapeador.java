package com.margem.api.web.dto;

import com.margem.api.dominio.*;
import com.margem.api.dominio.Enums.Formato;
import com.margem.api.dominio.Enums.Severidade;
import com.margem.api.dominio.Enums.StatusDocumento;
import com.margem.api.web.dto.Respostas.*;

import java.util.List;

/** Converte entidades nas respostas da API aplicando a escala de nota escolhida. */
public final class Mapeador {

    private Mapeador() {
    }

    public static DocumentoResumo resumo(Documento d) {
        return new DocumentoResumo(d.getId(), d.getNomeArquivo(), d.getFormato(), d.getTipoTrabalho(),
                d.getStatus(), etapa(d), d.getCriadoEm(), d.getAnalisadoEm(),
                nota(d.getNotaGeral(), d.getEscalaNota()), d.getFormatadoEm() != null);
    }

    public static DocumentoDetalhe detalhe(Documento d) {
        EscalaNota escala = d.getEscalaNota();
        List<Orientacao> os = d.getOrientacoes();
        int criticas = (int) os.stream().filter(o -> o.getSeveridade() == Severidade.CRITICO).count();
        int resolvidas = (int) os.stream().filter(Orientacao::isResolvida).count();

        return new DocumentoDetalhe(
                d.getId(), d.getNomeArquivo(), d.getFormato(), d.getTipoTrabalho(), d.getCurso(), escala,
                d.getStatus(), etapa(d), d.getErro(), d.getCriadoEm(), d.getAnalisadoEm(), d.getTotalPaginas(),
                nota(d.getNotaGeral(), escala),
                criterios(d.getNotas(), escala),
                d.getResumo(),
                new Contagem(os.size(), criticas, os.size() - criticas, resolvidas),
                os.stream().map(Mapeador::orientacao).toList(),
                formatacao(d));
    }

    public static OrientacaoDto orientacao(Orientacao o) {
        String local = o.getPagina() == null ? null
                : "Página " + o.getPagina() + (o.getParagrafo() == null ? "" : ", parágrafo " + o.getParagrafo());
        Correcao correcao = o.getCorrecaoOriginal() == null ? null
                : new Correcao(o.getCorrecaoOriginal(), o.getCorrecaoSugerida());
        return new OrientacaoDto(o.getId(), o.getSeveridade(), o.getSeveridade().rotulo,
                o.getCategoria(), o.getCategoria().rotulo, o.getTitulo(), o.getPagina(), o.getParagrafo(),
                local, o.getTrecho(), o.getPorQueImporta(), o.getComoAjustar(), correcao, o.isResolvida());
    }

    static Nota nota(Double nota, EscalaNota escala) {
        if (nota == null) return null;
        EscalaNota.Faixa f = EscalaNota.Faixa.de(nota);
        return new Nota(escala.exibir(nota), escala.valor(nota), escala.maximo(), nota * 10,
                new Faixa(f.codigo, f.nome, f.descricao, f.imagem));
    }

    static List<Criterio> criterios(NotasCriterios n, EscalaNota escala) {
        if (n == null || n.getEstrutura() == null) return List.of();
        return List.of(
                criterio("estrutura", "Estrutura", n.getEstrutura(), escala),
                criterio("argumentacao", "Argumentação", n.getArgumentacao(), escala),
                criterio("clareza", "Clareza", n.getClareza(), escala),
                criterio("citacoesReferencias", "Citações e referências", n.getCitacoesReferencias(), escala),
                criterio("formatacaoAbnt", "Formatação ABNT", n.getFormatacaoAbnt(), escala));
    }

    private static Criterio criterio(String chave, String rotulo, double nota, EscalaNota escala) {
        return new Criterio(chave, rotulo, escala.exibir(nota), nota * 10, EscalaNota.status(nota));
    }

    static Formatacao formatacao(Documento d) {
        String motivo = null;
        if (d.getFormato() == Formato.PDF) {
            motivo = "Este trabalho foi enviado em PDF. Para formatar em ABNT, envie o arquivo .docx.";
        } else if (d.getStatus() != StatusDocumento.CONCLUIDO) {
            motivo = "A formatação fica disponível depois da análise.";
        }
        boolean gerada = d.getFormatadoEm() != null;
        List<Alteracao> alteracoes = gerada
                ? d.getAlteracoesFormatacao().stream()
                    .map(a -> new Alteracao(a.getParagrafoIndice(), a.getPagina(), a.getTrecho(), a.getDescricao()))
                    .toList()
                : List.of();
        return new Formatacao(d.podeFormatar(), motivo, gerada, d.getFormatadoEm(),
                gerada ? "/api/documentos/" + d.getId() + "/formatacao/arquivo" : null,
                d.getCorrecoesAplicadas(), alteracoes);
    }

    static int etapa(Documento d) {
        if (d.getFormatadoEm() != null) return 4;
        return switch (d.getStatus()) {
            case RECEBIDO, ANALISANDO, ERRO -> 2;
            case CONCLUIDO -> 3;
        };
    }
}
