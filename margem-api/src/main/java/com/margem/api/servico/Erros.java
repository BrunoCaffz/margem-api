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

    /** Credenciais ou token inválidos (HTTP 401). A mensagem nunca diz qual parte falhou. */
    public static class NaoAutenticado extends RuntimeException {
        public NaoAutenticado(String mensagem) {
            super(mensagem);
        }
    }

    /** Autenticado, mas sem permissão para a operação (HTTP 403). */
    public static class Proibido extends RuntimeException {
        public Proibido(String mensagem) {
            super(mensagem);
        }
    }

    /** Limite de requisições excedido (HTTP 429). */
    public static class LimiteExcedido extends RuntimeException {
        private final long segundos;

        public LimiteExcedido(long segundos) {
            super("Muitas tentativas. Aguarde um pouco e tente de novo.");
            this.segundos = segundos;
        }

        public long segundos() {
            return segundos;
        }
    }
}
