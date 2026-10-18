package com.margem.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Swagger liberado em desenvolvimento e inexistente quando desligado (perfil prod). */
class SwaggerTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class Ligado {
        @Autowired
        MockMvc mvc;

        @Test
        void documentaRotasAutenticacaoErrosEBotaoAuthorize() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.info.title").value("Margem API"))
                    .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"))
                    .andExpect(jsonPath("$.paths['/api/documentos'].get.summary").exists())
                    .andExpect(jsonPath("$.security[0].bearer-jwt").exists())
                    .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                    .andExpect(jsonPath("$.paths['/api/documentos/{id}'].get.responses['404']").exists())
                    .andExpect(jsonPath("$.paths['/api/documentos'].post.responses['429']").exists())
                    .andExpect(jsonPath("$.paths['/api/documentos'].get.responses['401']").exists())
                    .andExpect(jsonPath("$.paths['/api/documentos'].get.responses['403']").exists())
                    .andExpect(jsonPath("$.paths['/api/auth/cadastro'].post.responses['409']").exists())
                    .andExpect(jsonPath("$.paths['/api/auth/cadastro'].post.responses['500']").exists());
        }

        @Test
        void interfaceCarregaSemCabecalhoCspQueBloquearia() throws Exception {
            mvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Content-Security-Policy"));
        }

        @Test
        void naoExpoeCredenciaisNaDocumentacao() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString(Sessoes.SEGREDO_TESTE))));
        }
    }

    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class Desligado {
        @Autowired
        MockMvc mvc;

        @Test
        void documentacaoNaoEstaDisponivelNemParaQuemTemToken() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
            mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
            String token = "Bearer " + Sessoes.tokenValido(java.util.UUID.randomUUID().toString(), "USER");
            mvc.perform(get("/v3/api-docs").header("Authorization", token)).andExpect(status().is4xxClientError());
        }
    }
}
