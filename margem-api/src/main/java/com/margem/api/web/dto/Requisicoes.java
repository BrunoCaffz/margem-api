package com.margem.api.web.dto;

import com.margem.api.dominio.EscalaNota;
import jakarta.validation.constraints.NotNull;

public final class Requisicoes {

    private Requisicoes() {
    }

    public record AtualizarDocumento(@NotNull EscalaNota escalaNota) {
    }

    public record AtualizarOrientacao(@NotNull Boolean resolvida) {
    }

    /** @param aplicarCorrecoes aplica na cópia as correções de português sugeridas pela IA */
    public record Formatar(Boolean aplicarCorrecoes) {
        public boolean aplicar() {
            return aplicarCorrecoes == null || aplicarCorrecoes;
        }
    }
}
