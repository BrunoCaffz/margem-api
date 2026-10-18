package com.margem.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "margem.seguranca")
public record SegurancaProperties(
        /** Segredo HS256, mínimo 32 bytes. Vem de JWT_SECRET; a aplicação não sobe sem ele. */
        String jwtSecret,
        String emissor,
        String audiencia,
        Duration accessTtl,
        Duration refreshTtl,
        boolean cookieSecure,
        String cookieSameSite,
        Limites limites
) {
    public record Limite(int capacidade, Duration janela) {
    }

    public record Limites(
            /** Cadastro, login e refresh, por IP. */
            Limite authIp,
            /** Tentativas de login por e-mail, existindo a conta ou não. */
            Limite loginEmail,
            /** Envio, reanálise e formatação (gastam OpenAI e CPU), por usuário. */
            Limite analiseUsuario,
            /** Demais chamadas autenticadas, por usuário. */
            Limite apiUsuario
    ) {
    }
}
