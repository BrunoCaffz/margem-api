package com.margem.api.web.dto;

import com.margem.api.dominio.EscalaNota;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class Requisicoes {

    private Requisicoes() {
    }

    public record AtualizarDocumento(@NotNull(message = "Informe a escala de nota.") EscalaNota escalaNota) {
    }

    public record AtualizarOrientacao(@NotNull(message = "Informe se a orientação foi resolvida.") Boolean resolvida) {
    }

    /** @param aplicarCorrecoes aplica na cópia as correções de português sugeridas pela IA */
    public record Formatar(Boolean aplicarCorrecoes) {
        public boolean aplicar() {
            return aplicarCorrecoes == null || aplicarCorrecoes;
        }
    }

    public record Cadastro(
            @NotBlank(message = "Informe o e-mail.")
            @Email(message = "E-mail inválido.")
            @Size(max = 254, message = "E-mail muito longo.")
            String email,
            @NotBlank(message = "Informe a senha.")
            @Size(min = 10, max = 72, message = "A senha precisa ter entre 10 e 72 caracteres.")
            String senha,
            @Size(max = 100, message = "O nome pode ter até 100 caracteres.")
            String nome) {
    }

    public record Login(
            @NotBlank(message = "Informe o e-mail.")
            @Size(max = 254, message = "E-mail inválido.")
            String email,
            @NotBlank(message = "Informe a senha.")
            @Size(max = 72, message = "Senha inválida.")
            String senha) {
    }
}
