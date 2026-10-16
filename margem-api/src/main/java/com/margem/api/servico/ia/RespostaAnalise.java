package com.margem.api.servico.ia;

import java.util.List;

/** Formato exato que a IA devolve (espelha prompts/analise-schema.json). */
public record RespostaAnalise(String resumo, Notas notas, List<OrientacaoIa> orientacoes) {

    public record Notas(double estrutura, double argumentacao, double clareza,
                        double citacoesReferencias, double formatacaoAbnt) {
    }

    public record OrientacaoIa(String severidade, String categoria, String titulo,
                               Integer pagina, Integer paragrafo, String trecho,
                               String porQueImporta, String comoAjustar, Correcao correcao) {
    }

    public record Correcao(String original, String sugerido) {
    }
}
