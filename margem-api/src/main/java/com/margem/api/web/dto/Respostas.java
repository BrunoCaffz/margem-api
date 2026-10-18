package com.margem.api.web.dto;

import com.margem.api.dominio.EscalaNota;
import com.margem.api.dominio.Enums.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tudo o que a API devolve, já no formato que as telas consomem. */
public final class Respostas {

    private Respostas() {
    }

    /** Item da lista "Seus trabalhos". */
    public record DocumentoResumo(
            UUID id,
            String nomeArquivo,
            Formato formato,
            TipoTrabalho tipoTrabalho,
            StatusDocumento status,
            int etapa,
            Instant criadoEm,
            Instant analisadoEm,
            Nota nota,
            boolean formatado
    ) {
    }

    /** Tela "Relatório de qualidade" (e base da tela "Formatação ABNT"). */
    public record DocumentoDetalhe(
            UUID id,
            String nomeArquivo,
            Formato formato,
            TipoTrabalho tipoTrabalho,
            String curso,
            EscalaNota escalaNota,
            StatusDocumento status,
            /** 1 Enviar, 2 Analisar, 3 Relatório, 4 Formatar ABNT */
            int etapa,
            String erro,
            Instant criadoEm,
            Instant analisadoEm,
            Integer totalPaginas,
            Nota nota,
            List<Criterio> criterios,
            String resumo,
            Contagem contagem,
            List<OrientacaoDto> orientacoes,
            Formatacao formatacao
    ) {
    }

    /**
     * @param exibicao texto pronto para a tela: "5,8", "58" ou "R"
     * @param valor    número na escala escolhida (nulo em conceito)
     * @param maximo   10, 100 ou nulo (o "de 10" abaixo do número)
     * @param percentual 0 a 100, para o anel e as barras
     */
    public record Nota(String exibicao, Double valor, Integer maximo, double percentual, Faixa faixa) {
    }

    /** Cartão com imagem: "R, Regular / Tem base, mas ainda faltam partes importantes." */
    public record Faixa(String codigo, String nome, String descricao, String imagem) {
    }

    /** Barra por critério: "Estrutura 6,5 Atenção". */
    public record Criterio(String chave, String rotulo, String exibicao, double percentual, String status) {
    }

    public record Contagem(int total, int criticas, int atencao, int resolvidas) {
    }

    public record OrientacaoDto(
            UUID id,
            Severidade severidade,
            String severidadeRotulo,
            Categoria categoria,
            String categoriaRotulo,
            String titulo,
            Integer pagina,
            Integer paragrafo,
            /** "Página 14, parágrafo 2" ou nulo quando vale para o trabalho todo */
            String localizacao,
            String trecho,
            String porQueImporta,
            String comoAjustar,
            Correcao correcao,
            boolean resolvida
    ) {
    }

    public record Correcao(String original, String sugerido) {
    }

    /**
     * @param disponivel  false para PDF ou análise não concluída
     * @param motivo      texto do aviso quando indisponível ("Este trabalho foi enviado em PDF...")
     * @param urlDownload caminho relativo para baixar o .docx formatado
     */
    public record Formatacao(
            boolean disponivel,
            String motivo,
            boolean gerada,
            Instant geradaEm,
            String urlDownload,
            int correcoesAplicadas,
            List<Alteracao> alteracoes
    ) {
    }

    /** paragrafoIndice -1 = alteração no documento todo (papel, margens). */
    public record Alteracao(int paragrafoIndice, Integer pagina, String trecho, String descricao) {
    }

    public record UsuarioDto(java.util.UUID id, String email, String nome) {
    }

    /** O refresh token não aparece aqui: vai num cookie HttpOnly. */
    public record Sessao(String accessToken, String tokenType, long expiresIn, UsuarioDto usuario) {
    }
}
