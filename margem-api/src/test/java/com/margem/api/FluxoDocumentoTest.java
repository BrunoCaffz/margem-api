package com.margem.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.servico.ia.OpenAiClient;
import com.margem.api.servico.ia.RespostaAnalise;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fluxo completo das telas com a OpenAI simulada: enviar → analisar → relatório → formatar → baixar → apagar. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FluxoDocumentoTest {

    static final String CLIENTE = "aluno-teste-0001";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    OpenAiClient openAi;

    @Test
    void fluxoCompletoDocx() throws Exception {
        when(openAi.completarJson(anyString(), anyString(), anyString(), any(), eq(RespostaAnalise.class)))
                .thenReturn(new RespostaAnalise(
                        "Tem base, mas a pergunta de pesquisa aparece tarde.",
                        new RespostaAnalise.Notas(6.5, 5.2, 6.0, 5.5, 5.8),
                        List.of(
                                new RespostaAnalise.OrientacaoIa("ATENCAO", "PORTUGUES", "Erro de ortografia",
                                        2, 2, "Este trabalho analiza", "Revisão de ortografia.",
                                        "Troque por \"analisa\".", new RespostaAnalise.Correcao("analiza", "analisa")),
                                new RespostaAnalise.OrientacaoIa("CRITICO", "CITACOES_REFERENCIAS",
                                        "Citação direta sem número de página", 2, 3,
                                        "Segundo Silva, a metodologia deve ser clara e replicável",
                                        "A NBR 10520 exige a página em citações diretas.",
                                        "Acrescente a página depois do ano, por exemplo (SILVA, 2020, p. 45).", null))));

        var arquivo = new MockMultipartFile("arquivo", "meu tcc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocxTeste.tccSimples());

        String criado = mvc.perform(multipart("/api/documentos").file(arquivo)
                        .param("tipoTrabalho", "TCC").param("escalaNota", "DEZ")
                        .header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(criado).get("id").asText();

        JsonNode rel = aguardarConclusao(id);
        assertThat(rel.get("nota").get("exibicao").asText()).isEqualTo("5,8");
        assertThat(rel.get("nota").get("faixa").get("codigo").asText()).isEqualTo("R");
        assertThat(rel.get("criterios")).hasSize(5);
        assertThat(rel.get("contagem").get("criticas").asInt()).isEqualTo(1);
        // críticas primeiro
        assertThat(rel.get("orientacoes").get(0).get("severidadeRotulo").asText()).isEqualTo("Crítico");
        assertThat(rel.get("orientacoes").get(0).get("localizacao").asText()).isEqualTo("Página 2, parágrafo 3");
        assertThat(rel.get("formatacao").get("disponivel").asBoolean()).isTrue();

        // muda escala sem reanalisar
        mvc.perform(patch("/api/documentos/" + id).header("X-Cliente-Id", CLIENTE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"escalaNota\":\"CEM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nota.exibicao").value("58"));

        // marcar como resolvido
        String orientacaoId = rel.get("orientacoes").get(0).get("id").asText();
        mvc.perform(patch("/api/documentos/" + id + "/orientacoes/" + orientacaoId).header("X-Cliente-Id", CLIENTE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolvida\":true}"))
                .andExpect(jsonPath("$.resolvida").value(true));

        // formatar ABNT e baixar
        mvc.perform(post("/api/documentos/" + id + "/formatacao").header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etapa").value(4))
                .andExpect(jsonPath("$.formatacao.correcoesAplicadas").value(1))
                .andExpect(jsonPath("$.formatacao.alteracoes").isNotEmpty());

        byte[] baixado = mvc.perform(get("/api/documentos/" + id + "/formatacao/arquivo").header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ABNT")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(baixado).startsWith((byte) 'P', (byte) 'K');

        // outro aluno não enxerga
        mvc.perform(get("/api/documentos/" + id).header("X-Cliente-Id", "outro-aluno-0002"))
                .andExpect(status().isNotFound());

        // lista e apaga
        mvc.perform(get("/api/documentos").header("X-Cliente-Id", CLIENTE))
                .andExpect(jsonPath("$[0].nota.exibicao").value("58"));
        mvc.perform(delete("/api/documentos/" + id).header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documentos/" + id).header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejeitaArquivoQueNaoEDocxNemPdf() throws Exception {
        var falso = new MockMultipartFile("arquivo", "virus.docx", "application/octet-stream", "nao sou zip".getBytes());
        mvc.perform(multipart("/api/documentos").file(falso).header("X-Cliente-Id", CLIENTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Envie um arquivo .docx ou .pdf."));
    }

    private JsonNode aguardarConclusao(String id) throws Exception {
        for (int i = 0; i < 50; i++) {
            String corpo = mvc.perform(get("/api/documentos/" + id).header("X-Cliente-Id", CLIENTE))
                    .andReturn().getResponse().getContentAsString();
            JsonNode n = json.readTree(corpo);
            String status = n.get("status").asText();
            if (status.equals("CONCLUIDO")) return n;
            if (status.equals("ERRO")) throw new AssertionError("Análise falhou: " + n.get("erro"));
            Thread.sleep(100);
        }
        throw new AssertionError("Análise não terminou a tempo");
    }
}
