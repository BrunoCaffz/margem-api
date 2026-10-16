package com.margem.api.servico.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Chamada à Chat Completions API da OpenAI com Structured Outputs (json_schema estrito):
 * a resposta sempre vem no formato do schema, sem precisar "consertar" JSON.
 * Faz novas tentativas em 429 (limite de uso) e 5xx.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final RestClient rest;
    private final OpenAiProperties props;
    private final ObjectMapper mapper;

    public OpenAiClient(RestClient.Builder builder, OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(15));
        fabrica.setReadTimeout(props.timeout());
        this.rest = builder
                .baseUrl(props.baseUrl())
                .requestFactory(fabrica)
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
    }

    public <T> T completarJson(String instrucoes, String conteudo, String nomeSchema,
                               JsonNode schema, Class<T> tipo) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new FalhaIaException("OPENAI_API_KEY não configurada no servidor.", false);
        }

        Map<String, Object> corpo = Map.of(
                "model", props.modelo(),
                "messages", List.of(
                        Map.of("role", "system", "content", instrucoes),
                        Map.of("role", "user", "content", conteudo)),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of("name", nomeSchema, "strict", true, "schema", schema)));

        int tentativas = Math.max(1, props.tentativas());
        for (int tentativa = 1; ; tentativa++) {
            try {
                JsonNode resposta = rest.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(corpo)
                        .retrieve()
                        .body(JsonNode.class);
                return ler(resposta, tipo);
            } catch (RestClientResponseException e) {
                boolean repetir = deveRepetir(e.getStatusCode()) && tentativa < tentativas;
                log.warn("OpenAI respondeu {} (tentativa {}/{}): {}", e.getStatusCode().value(),
                        tentativa, tentativas, resumir(e.getResponseBodyAsString()));
                if (!repetir) {
                    throw new FalhaIaException("A IA não respondeu (HTTP " + e.getStatusCode().value() + ").",
                            deveRepetir(e.getStatusCode()));
                }
                esperar(tentativa);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                log.warn("Falha de rede com a OpenAI (tentativa {}/{}): {}", tentativa, tentativas, e.getMessage());
                if (tentativa >= tentativas) throw new FalhaIaException("Sem conexão com a IA.", true);
                esperar(tentativa);
            }
        }
    }

    private <T> T ler(JsonNode resposta, Class<T> tipo) {
        JsonNode escolha = resposta.path("choices").path(0);
        JsonNode mensagem = escolha.path("message");
        if (mensagem.hasNonNull("refusal")) {
            throw new FalhaIaException("A IA recusou analisar este conteúdo.", false);
        }
        if ("length".equals(escolha.path("finish_reason").asText())) {
            throw new FalhaIaException("A resposta da IA ficou grande demais e foi cortada.", true);
        }
        try {
            return mapper.readValue(mensagem.path("content").asText(), tipo);
        } catch (Exception e) {
            throw new FalhaIaException("A IA devolveu um formato inesperado.", true);
        }
    }

    private static boolean deveRepetir(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private static void esperar(int tentativa) {
        try {
            Thread.sleep(Duration.ofSeconds((long) Math.pow(2, tentativa) * 2));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new FalhaIaException("Análise interrompida.", true);
        }
    }

    private static String resumir(String s) {
        return s == null ? "" : s.substring(0, Math.min(400, s.length()));
    }

    public static class FalhaIaException extends RuntimeException {
        private final boolean temporaria;

        public FalhaIaException(String mensagem, boolean temporaria) {
            super(mensagem);
            this.temporaria = temporaria;
        }

        public boolean isTemporaria() {
            return temporaria;
        }
    }
}
