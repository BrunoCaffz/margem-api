package com.margem.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA = "bearer-jwt";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Margem API")
                        .version("0.1.0")
                        .description("""
                                Avaliação de trabalhos acadêmicos com IA e formatação ABNT.

                                Para testar as rotas protegidas: faça POST /api/auth/login, copie o `accessToken` \
                                da resposta e cole no botão **Authorize**. O token vale por poucos minutos; \
                                a renovação usa um cookie HttpOnly e é feita pelo front."""))
                .components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access token JWT devolvido por /api/auth/login ou /api/auth/cadastro.")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA));
    }

    /** Todas as rotas podem responder estes erros, sempre em problem+json. */
    @Bean
    OpenApiCustomizer errosPadrao() {
        Schema<?> problema = new ObjectSchema()
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Bad Request"))
                .addProperty("status", new Schema<Integer>().type("integer").example(400))
                .addProperty("detail", new StringSchema().example("Dados inválidos na requisição."));
        Map<String, String> erros = new java.util.LinkedHashMap<>();
        erros.put("400", "Dados inválidos");
        erros.put("401", "Sem token, token inválido ou expirado");
        erros.put("403", "Sem permissão");
        erros.put("404", "Não encontrado (inclui trabalhos de outros usuários)");
        erros.put("409", "Conflito com o estado atual");
        erros.put("429", "Limite de requisições excedido (veja o cabeçalho Retry-After)");
        erros.put("500", "Erro inesperado");
        return openApi -> openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op ->
                erros.forEach((codigo, descricao) -> {
                    if (op.getResponses().containsKey(codigo)) return;
                    op.getResponses().addApiResponse(codigo, new ApiResponse().description(descricao)
                            .content(new Content().addMediaType("application/problem+json", new MediaType().schema(problema))));
                })));
    }
}
