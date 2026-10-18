package com.margem.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Apoio dos testes: cria contas reais pela API e forja tokens para os cenários de ataque. */
public final class Sessoes {

    public static final String SENHA = "Senha-Forte-123";
    public static final String SEGREDO_TESTE = "segredo-somente-para-testes-automatizados-0123456789";

    public record Sessao(String email, String id, String accessToken, Cookie refresh) {
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    private Sessoes() {
    }

    public static String emailNovo() {
        return "aluno-" + UUID.randomUUID() + "@exemplo.com";
    }

    public static Sessao cadastrar(MockMvc mvc, ObjectMapper json) throws Exception {
        String email = emailNovo();
        MvcResult r = mvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\",\"nome\":\"Aluno Teste\"}"))
                .andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("Cadastro falhou: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        return new Sessao(email, n.get("usuario").get("id").asText(), n.get("accessToken").asText(),
                r.getResponse().getCookie("margem_refresh"));
    }

    /** Token assinado com a chave informada. Com o segredo certo e claims erradas, testa expiração, emissor e audiência. */
    public static String forjar(String segredo, String emissor, String audiencia, Instant expira, String sub, String papel) {
        var chave = new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var claims = JwtClaimsSet.builder().issuer(emissor).audience(List.of(audiencia)).subject(sub)
                .issuedAt(expira.minusSeconds(600)).expiresAt(expira).claim("papel", papel).build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static String tokenValido(String sub, String papel) {
        return forjar(SEGREDO_TESTE, "margem-api", "margem-front", Instant.now().plusSeconds(600), sub, papel);
    }
}
