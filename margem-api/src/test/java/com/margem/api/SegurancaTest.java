package com.margem.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.Sessoes.Sessao;
import com.margem.api.servico.ia.OpenAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolamento entre usuários (IDOR/BOLA), validação de entrada, cabeçalhos HTTP, CORS e rotas públicas. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SegurancaTest {

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    OpenAiClient openAi; // a análise em segundo plano falha de propósito; aqui só importa o dono do registro

    Sessao ana;
    Sessao beto;
    String docDaAna;

    @BeforeEach
    void preparar() throws Exception {
        ana = Sessoes.cadastrar(mvc, json);
        beto = Sessoes.cadastrar(mvc, json);
        docDaAna = enviar(ana, "tcc-da-ana.docx");
    }

    // ---------------------------------------------------------------- IDOR / BOLA

    @Test
    void outroUsuarioNaoAcessaNenhumaRotaDoDocumentoDaAna() throws Exception {
        String b = beto.bearer();
        String base = "/api/documentos/" + docDaAna;
        String orientacao = UUID.randomUUID().toString();

        mvc.perform(get(base).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(patch(base).header("Authorization", b).contentType(MediaType.APPLICATION_JSON)
                .content("{\"escalaNota\":\"CEM\"}")).andExpect(status().isNotFound());
        mvc.perform(patch(base + "/orientacoes/" + orientacao).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content("{\"resolvida\":true}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/reanalise").header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/formatacao").header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/formatacao/arquivo").header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(delete(base).header("Authorization", b)).andExpect(status().isNotFound());

        // a lista do Beto não traz o documento da Ana, e o documento continua lá para ela
        mvc.perform(get("/api/documentos").header("Authorization", b))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(base).header("Authorization", ana.bearer())).andExpect(status().isOk());
    }

    @Test
    void respostaDeOutroUsuarioNaoDiferenciaDeIdInexistente() throws Exception {
        String alheio = mvc.perform(get("/api/documentos/" + docDaAna).header("Authorization", beto.bearer()))
                .andReturn().getResponse().getContentAsString();
        String inexistente = mvc.perform(get("/api/documentos/" + UUID.randomUUID()).header("Authorization", beto.bearer()))
                .andReturn().getResponse().getContentAsString();
        var a = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(alheio);
        var b = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(inexistente);
        a.remove("instance");
        b.remove("instance");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void identificadorDoDonoEnviadoPeloClienteEIgnorado() throws Exception {
        // cabeçalhos e parâmetros que o antigo X-Cliente-Id aceitava não dão acesso a nada
        mvc.perform(get("/api/documentos/" + docDaAna).header("Authorization", beto.bearer())
                        .header("X-Cliente-Id", ana.id()).param("usuarioId", ana.id()).param("userId", ana.id()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/documentos").header("X-Cliente-Id", ana.id())).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenValidoDeUsuarioQueNaoExisteNaoAcessaNada() throws Exception {
        String fantasma = "Bearer " + Sessoes.tokenValido(UUID.randomUUID().toString(), "USER");
        mvc.perform(get("/api/documentos/" + docDaAna).header("Authorization", fantasma)).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").header("Authorization", fantasma)).andExpect(status().isUnauthorized());
    }

    @Test
    void respostaNaoVazaDadosInternos() throws Exception {
        mvc.perform(get("/api/documentos/" + docDaAna).header("Authorization", ana.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caminhoArquivo").doesNotExist())
                .andExpect(jsonPath("$.usuarioId").doesNotExist())
                .andExpect(content().string(not(containsString("data/arquivos"))))
                .andExpect(content().string(not(containsString(ana.id()))));
    }

    // ---------------------------------------------------------------- validação de entrada

    @Test
    void identificadoresMalformadosDao400() throws Exception {
        for (String id : new String[]{"abc", "1", "1%20OR%201=1", "..%2F..%2Fetc%2Fpasswd", "00000000-0000-0000-0000-00000000000g"}) {
            mvc.perform(get("/api/documentos/" + id).header("Authorization", ana.bearer()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void parametroLimiteForaDaFaixaDa400() throws Exception {
        for (String limite : new String[]{"0", "-1", "101", "99999999999", "abc", "1;DROP TABLE documento"}) {
            mvc.perform(get("/api/documentos").param("limite", limite).header("Authorization", ana.bearer()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/documentos").param("limite", "100").header("Authorization", ana.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void enumInvalidoEJsonRuimDao400() throws Exception {
        String url = "/api/documentos/" + docDaAna;
        for (String corpo : new String[]{"{\"escalaNota\":\"MIL\"}", "{}", "{\"escalaNota\":null}", "[", "texto"}) {
            mvc.perform(patch(url).header("Authorization", ana.bearer()).contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/documentos").file(docx("a.docx")).param("tipoTrabalho", "INVENTADO")
                .header("Authorization", ana.bearer())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").file(docx("a.docx")).param("escalaNota", "MIL")
                .header("Authorization", ana.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void propriedadesExtrasNoJsonNaoAlteramCamposInternos() throws Exception {
        // mass assignment: só escalaNota é lido; dono, nota e status enviados no corpo são descartados
        mvc.perform(patch("/api/documentos/" + docDaAna).header("Authorization", ana.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"escalaNota\":\"CEM\",\"usuarioId\":\"" + beto.id() + "\",\"status\":\"CONCLUIDO\",\"notaGeral\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalaNota").value("CEM"))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.not("CONCLUIDO")));
        mvc.perform(get("/api/documentos/" + docDaAna).header("Authorization", beto.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void sqlInjectionNosCamposDeTextoEGravadoComoTextoPuro() throws Exception {
        String sqli = "x'); DROP TABLE documento; --";
        mvc.perform(multipart("/api/documentos").file(docx("tcc.docx")).param("curso", sqli)
                        .header("Authorization", ana.bearer()))
                .andExpect(status().isAccepted());
        // a tabela continua existindo e os dados da Ana continuam acessíveis
        mvc.perform(get("/api/documentos").header("Authorization", ana.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cursoMuitoLongoDa400() throws Exception {
        mvc.perform(multipart("/api/documentos").file(docx("tcc.docx")).param("curso", "c".repeat(151))
                        .header("Authorization", ana.bearer()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- uploads

    @Test
    void nomeDeArquivoComTraversalEReduzidoAoNomeBase() throws Exception {
        String resp = mvc.perform(multipart("/api/documentos").file(docx("../../../etc/passwd.docx"))
                        .header("Authorization", ana.bearer()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(resp).get("nomeArquivo").asText()).isEqualTo("passwd.docx");
        String win = mvc.perform(multipart("/api/documentos").file(docx("..\\..\\windows\\system32\\x.docx"))
                        .header("Authorization", ana.bearer()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(win).get("nomeArquivo").asText()).isEqualTo("x.docx");
    }

    @Test
    void uploadsInvalidosSaoRecusados() throws Exception {
        String a = ana.bearer();
        // extensão e conteúdo não batem, executável disfarçado, vazio, .doc antigo, sem arquivo
        mvc.perform(multipart("/api/documentos").file(new MockMultipartFile("arquivo", "x.docx", DOCX, "MZ\u0090\u0000".getBytes()))
                .header("Authorization", a)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").file(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", DocxTeste.tccSimples()))
                .header("Authorization", a)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").file(new MockMultipartFile("arquivo", "x.exe", DOCX, DocxTeste.tccSimples()))
                .header("Authorization", a)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").file(new MockMultipartFile("arquivo", "x.docx", DOCX, new byte[0]))
                .header("Authorization", a)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").file(new MockMultipartFile("arquivo", "x.doc", "application/msword", DocxTeste.tccSimples()))
                .header("Authorization", a)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documentos").header("Authorization", a)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- cabeçalhos, erros, CORS, rotas públicas

    @Test
    void respostasTemCabecalhosDeSeguranca() throws Exception {
        mvc.perform(get("/api/documentos").header("Authorization", ana.bearer()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().exists("Permissions-Policy"));
        // HSTS só é enviado em conexão segura
        mvc.perform(get("/api/documentos").secure(true).header("Authorization", ana.bearer()))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void errosNaoVazamDetalhesInternos() throws Exception {
        mvc.perform(get("/api/nao-existe").header("Authorization", ana.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("com.margem"))));
        mvc.perform(put("/api/documentos").header("Authorization", ana.bearer()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/documentos").contentType(MediaType.TEXT_PLAIN).content("x").header("Authorization", ana.bearer()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void corsSoLiberaAsOrigensConfiguradas() throws Exception {
        mvc.perform(options("/api/documentos").header("Origin", "https://malicioso.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/documentos").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void soAsRotasPublicasPrevistasSaoPublicas() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("components"))));
        for (String rota : new String[]{"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/h2-console", "/qualquer-coisa"}) {
            mvc.perform(get(rota)).andExpect(status().isUnauthorized());
            mvc.perform(get(rota).header("Authorization", ana.bearer())).andExpect(status().is4xxClientError());
        }
    }

    // ---------------------------------------------------------------- apoio

    private String enviar(Sessao s, String nome) throws Exception {
        String r = mvc.perform(multipart("/api/documentos").file(docx(nome)).header("Authorization", s.bearer()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("id").asText();
    }

    private static MockMultipartFile docx(String nome) throws Exception {
        return new MockMultipartFile("arquivo", nome, DOCX, DocxTeste.tccSimples());
    }
}
