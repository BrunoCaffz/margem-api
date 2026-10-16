package com.margem.api.dominio;

/**
 * Enums do domínio agrupados num só arquivo para facilitar a leitura.
 */
public final class Enums {

    private Enums() {
    }

    public enum TipoTrabalho {
        TCC("TCC"), ARTIGO("Artigo"), RELATORIO("Relatório"), RESENHA("Resenha");

        public final String rotulo;

        TipoTrabalho(String rotulo) {
            this.rotulo = rotulo;
        }
    }

    public enum Formato { DOCX, PDF }

    /** Etapas que o front mostra na barra de progresso (Enviar, Analisar, Relatório). */
    public enum StatusDocumento { RECEBIDO, ANALISANDO, CONCLUIDO, ERRO }

    public enum Severidade {
        CRITICO("Crítico"), ATENCAO("Atenção");

        public final String rotulo;

        Severidade(String rotulo) {
            this.rotulo = rotulo;
        }
    }

    public enum Categoria {
        ESTRUTURA("Estrutura"),
        ARGUMENTACAO("Argumentação"),
        CLAREZA("Clareza"),
        CITACOES_REFERENCIAS("Citações e referências"),
        FORMATACAO_ABNT("Formatação ABNT"),
        PORTUGUES("Português");

        public final String rotulo;

        Categoria(String rotulo) {
            this.rotulo = rotulo;
        }
    }
}
