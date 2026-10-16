package com.margem.api.servico;

public final class Erros {

    private Erros() {
    }

    public static class NaoEncontrado extends RuntimeException {
        public NaoEncontrado(String mensagem) {
            super(mensagem);
        }
    }

    public static class Invalido extends RuntimeException {
        public Invalido(String mensagem) {
            super(mensagem);
        }
    }

    /** Ação não permitida no estado atual (ex.: formatar um PDF). */
    public static class Conflito extends RuntimeException {
        public Conflito(String mensagem) {
            super(mensagem);
        }
    }
}
