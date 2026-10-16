package com.margem.api.dominio;

import java.util.Locale;

/**
 * Como o aluno quer ver a nota. Internamente a nota é sempre guardada de 0 a 10;
 * a escala só muda a exibição, então trocar de escala depois não exige nova análise.
 */
public enum EscalaNota {
    CONCEITO, DEZ, CEM;

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public String exibir(double notaDe0a10) {
        return switch (this) {
            case CONCEITO -> Faixa.de(notaDe0a10).codigo;
            case DEZ -> String.format(PT_BR, "%.1f", notaDe0a10);
            case CEM -> String.valueOf(Math.round(notaDe0a10 * 10));
        };
    }

    /** Valor numérico na escala escolhida (nulo para conceito). */
    public Double valor(double notaDe0a10) {
        return switch (this) {
            case CONCEITO -> null;
            case DEZ -> Math.round(notaDe0a10 * 10) / 10.0;
            case CEM -> (double) Math.round(notaDe0a10 * 10);
        };
    }

    public Integer maximo() {
        return switch (this) {
            case CONCEITO -> null;
            case DEZ -> 10;
            case CEM -> 100;
        };
    }

    /** Faixas I, R, B e MB. Cada uma tem uma imagem própria no front (campo imagem). */
    public enum Faixa {
        I("I", "Insuficiente", "Ainda não atende ao básico do trabalho. Comece pelas orientações críticas.", "insuficiente", 0.0),
        R("R", "Regular", "Tem base, mas ainda faltam partes importantes.", "regular", 5.0),
        B("B", "Bom", "Bem encaminhado. Faltam ajustes pontuais.", "bom", 7.0),
        MB("MB", "Muito bom", "Pronto para a banca, com poucos detalhes a revisar.", "muito-bom", 8.5);

        public final String codigo;
        public final String nome;
        public final String descricao;
        public final String imagem;
        public final double minimo;

        Faixa(String codigo, String nome, String descricao, String imagem, double minimo) {
            this.codigo = codigo;
            this.nome = nome;
            this.descricao = descricao;
            this.imagem = imagem;
            this.minimo = minimo;
        }

        public static Faixa de(double nota) {
            if (nota >= MB.minimo) return MB;
            if (nota >= B.minimo) return B;
            if (nota >= R.minimo) return R;
            return I;
        }
    }

    /** Selo ao lado de cada critério ("Atenção" nas telas). */
    public static String status(double nota) {
        if (nota < 5.0) return "Crítico";
        if (nota < 7.0) return "Atenção";
        return "Bom";
    }
}
