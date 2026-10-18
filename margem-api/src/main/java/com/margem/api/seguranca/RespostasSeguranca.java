package com.margem.api.seguranca;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 401, 403 e 429 geradas pelos filtros do Spring Security, no mesmo formato problem+json do resto da API. */
@Component
public class RespostasSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public RespostasSeguranca(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        res.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        escrever(res, HttpStatus.UNAUTHORIZED, "Entre na sua conta para continuar.");
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e) throws IOException {
        escrever(res, HttpStatus.FORBIDDEN, "Você não tem permissão para fazer isso.");
    }

    public void limiteExcedido(HttpServletResponse res, long segundos) throws IOException {
        res.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(segundos));
        escrever(res, HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde um pouco e tente de novo.");
    }

    private void escrever(HttpServletResponse res, HttpStatus status, String detalhe) throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(status.getReasonPhrase());
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), p);
    }
}
