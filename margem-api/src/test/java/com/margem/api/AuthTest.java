package com.margem.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.Sessoes.Sessao;
import com.margem.api.repositorio.UsuarioRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cadastro, login, tokens (assinatura, emissor, audiência, expiração), refresh rotativo e logout. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    UsuarioRepository usuarios;

    // ---------------------------------------------------------------- 401 / 403

    @Test
    void rotasPrivadasSemTokenDao401() throws Exception {
        for (String rota : new String[]{"/api/documentos", "/api/auth/me", "/api/documentos/" + UUID.randomUUID()}) {
            mvc.perform(get(rota))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        mvc.perform(post("/api/documentos")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenAdulteradoOuInvalidoDa401() throws Exception {
        String sub = UUID.randomUUID().toString();
        Instant futuro = Instant.now().plusSeconds(600);
        String[] ruins = {
                "lixo",
                // assinado com outra chave, tentando se passar por ADMIN
                Sessoes.forjar("outro-segredo-outro-segredo-outro-segredo-1234", "margem-api", "margem-front", futuro, sub, "ADMIN"),
                // expirado (além da tolerância de relógio)
                Sessoes.forjar(Sessoes.SEGREDO_TESTE, "margem-api", "margem-front", Instant.now().minusSeconds(3600), sub, "USER"),
                // emissor e audiência errados
                Sessoes.forjar(Sessoes.SEGREDO_TESTE, "outro-emissor", "margem-front", futuro, sub, "USER"),
                Sessoes.forjar(Sessoes.SEGREDO_TESTE, "margem-api", "outro-publico", futuro, sub, "USER"),
                // alg=none
                b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64("{\"sub\":\"" + sub + "\",\"papel\":\"ADMIN\"}") + ".",
        };
        for (String t : ruins) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void tokenNaQueryStringNaoEAceito() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        mvc.perform(get("/api/auth/me").param("access_token", s.accessToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioComumNaoAcessaAreaAdministrativa() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        mvc.perform(get("/api/admin/usuarios").header("Authorization", s.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void papelNaoVemDoClienteNemDeJwtForjado() throws Exception {
        // cadastro com propriedades extras "papel" e "admin": ignoradas, a conta nasce USER
        String email = Sessoes.emailNovo();
        String resp = mvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"" + Sessoes.SENHA + "\",\"papel\":\"ADMIN\",\"admin\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(resp).get("accessToken").asText();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(json.readTree(payload).get("papel").asText()).isEqualTo("USER");
        mvc.perform(get("/api/admin/x").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        // token ADMIN com chave errada é rejeitado antes de qualquer decisão de permissão
        String forjado = Sessoes.forjar("x".repeat(40), "margem-api", "margem-front", Instant.now().plusSeconds(600),
                UUID.randomUUID().toString(), "ADMIN");
        mvc.perform(get("/api/admin/x").header("Authorization", "Bearer " + forjado)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- cadastro e login

    @Test
    void cadastroLoginEMe() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        mvc.perform(get("/api/auth/me").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(s.email()))
                .andExpect(jsonPath("$.senhaHash").doesNotExist())
                .andExpect(jsonPath("$.papel").doesNotExist());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + s.email().toUpperCase() + "\",\"senha\":\"" + Sessoes.SENHA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(600))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void senhaErradaEEmailInexistenteDaoARespostaIdentica() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        String errada = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + s.email() + "\",\"senha\":\"senha-errada-123\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String inexistente = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + Sessoes.emailNovo() + "\",\"senha\":\"senha-errada-123\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(errada)).isEqualTo(json.readTree(inexistente));
    }

    @Test
    void emailDuplicadoDa409() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        mvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + s.email().toUpperCase() + "\",\"senha\":\"" + Sessoes.SENHA + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void senhaFicaSoComoHashBcrypt() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        String hash = usuarios.findByEmail(s.email()).orElseThrow().getSenhaHash();
        assertThat(hash).startsWith("$2").doesNotContain(Sessoes.SENHA);
    }

    // ---------------------------------------------------------------- refresh e logout

    @Test
    void cookieDeRefreshTemFlagsSeguras() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        Cookie c = s.refresh();
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo("/api/auth");
        assertThat(c.getMaxAge()).isEqualTo(14 * 24 * 3600);
        String bruto = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + s.email() + "\",\"senha\":\"" + Sessoes.SENHA + "\"}"))
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(bruto).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api/auth");
    }

    @Test
    void refreshGiraOTokenEReusoRevogaTudo() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);

        var r1 = mvc.perform(post("/api/auth/refresh").cookie(s.refresh()).header("X-Margem-Csrf", "1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie novo = r1.getCookie("margem_refresh");
        assertThat(novo.getValue()).isNotEqualTo(s.refresh().getValue());
        JsonNode corpo = json.readTree(r1.getContentAsString());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + corpo.get("accessToken").asText()))
                .andExpect(status().isOk());

        // o token antigo já foi usado: reapresentá-lo é sinal de cópia e derruba também a sessão nova
        mvc.perform(post("/api/auth/refresh").cookie(s.refresh()).header("X-Margem-Csrf", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(novo).header("X-Margem-Csrf", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshELogoutExigemCabecalhoAntiCsrfECookieValido() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        mvc.perform(post("/api/auth/refresh").cookie(s.refresh())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(s.refresh())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").header("X-Margem-Csrf", "1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("margem_refresh", "inventado")).header("X-Margem-Csrf", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevogaORefreshToken() throws Exception {
        Sessao s = Sessoes.cadastrar(mvc, json);
        var resp = mvc.perform(post("/api/auth/logout").cookie(s.refresh()).header("X-Margem-Csrf", "1"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(resp.getCookie("margem_refresh").getMaxAge()).isZero();
        mvc.perform(post("/api/auth/refresh").cookie(s.refresh()).header("X-Margem-Csrf", "1"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- validação

    @Test
    void cadastroInvalidoDa400() throws Exception {
        String[] ruins = {
                "{}",
                "{\"email\":\"nao-e-email\",\"senha\":\"" + Sessoes.SENHA + "\"}",
                "{\"email\":\"a@b.com\",\"senha\":\"curta\"}",
                "{\"email\":\"a@b.com\",\"senha\":\"" + "x".repeat(73) + "\"}",
                "{\"email\":\"" + "a".repeat(250) + "@b.com\",\"senha\":\"" + Sessoes.SENHA + "\"}",
                "{\"email\":\"a@b.com\",\"senha\":\"" + Sessoes.SENHA + "\",\"nome\":\"" + "n".repeat(101) + "\"}",
                "{\"email\":\"a@b.com\",\"senha\":\"" + "á".repeat(38) + "\"}", // 38 caracteres, 76 bytes
                "{isso nao e json",
        };
        for (String corpo : ruins) {
            mvc.perform(post("/api/auth/cadastro").contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void sqlInjectionNoLoginDa401SemErro500() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x' OR '1'='1' --\",\"senha\":\"x' OR '1'='1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginTemLimitePorEmail() throws Exception {
        String corpo = "{\"email\":\"" + Sessoes.emailNovo() + "\",\"senha\":\"senha-errada-123\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }
}
