package com.margem.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.Sessoes.Sessao;
import com.margem.api.servico.ia.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Limites pequenos só neste contexto de teste: IP nas rotas de login, usuário nas demais. */
@SpringBootTest(properties = {
        "margem.seguranca.limites.auth-ip.capacidade=3",
        "margem.seguranca.limites.analise-usuario.capacidade=2",
        "margem.seguranca.limites.api-usuario.capacidade=4"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LimiteRequisicoesTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    OpenAiClient openAi;

    @Test
    void rotasDeAuthLimitamPorIpEIgnoramXForwardedFor() throws Exception {
        String ip = "203.0.113.7";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                            .header("X-Forwarded-For", "198.51.100." + i) // tentativa de trocar de IP a cada chamada
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + Sessoes.emailNovo() + "\",\"senha\":\"senha-errada-123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + Sessoes.emailNovo() + "\",\"senha\":\"senha-errada-123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // outro IP não é afetado
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("203.0.113.8"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + Sessoes.emailNovo() + "\",\"senha\":\"senha-errada-123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void envioDeDocumentosTemLimiteProprioPorUsuario() throws Exception {
        Sessao ana = cadastrarEmOutroIp("203.0.113.20");
        Sessao beto = cadastrarEmOutroIp("203.0.113.21");
        var arquivo = new MockMultipartFile("arquivo", "tcc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocxTeste.tccSimples());

        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/api/documentos").file(arquivo).header("Authorization", ana.bearer()))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(multipart("/api/documentos").file(arquivo).header("Authorization", ana.bearer()))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // o limite é por usuário: o Beto envia normalmente
        mvc.perform(multipart("/api/documentos").file(arquivo).header("Authorization", beto.bearer()))
                .andExpect(status().isAccepted());
    }

    @Test
    void demaisRotasAutenticadasTambemTemTeto() throws Exception {
        Sessao ana = cadastrarEmOutroIp("203.0.113.30");
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/documentos").header("Authorization", ana.bearer())).andExpect(status().isOk());
        }
        mvc.perform(get("/api/documentos").header("Authorization", ana.bearer())).andExpect(status().isTooManyRequests());
    }

    private Sessao cadastrarEmOutroIp(String ip) throws Exception {
        String email = Sessoes.emailNovo();
        var r = mvc.perform(post("/api/auth/cadastro").with(req -> { req.setRemoteAddr(ip); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"" + Sessoes.SENHA + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var n = json.readTree(r.getContentAsString());
        return new Sessao(email, n.get("usuario").get("id").asText(), n.get("accessToken").asText(), r.getCookie("margem_refresh"));
    }
}
