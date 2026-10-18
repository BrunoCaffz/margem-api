package com.margem.api.web;

import com.margem.api.servico.Erros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Erros no formato RFC 9457 (application/problem+json). O campo "detail" já é uma
 * frase pronta para mostrar ao aluno.
 */
@RestControllerAdvice
public class TratadorErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorErros.class);

    @ExceptionHandler(Erros.NaoEncontrado.class)
    ProblemDetail naoEncontrado(Erros.NaoEncontrado e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Erros.Invalido.class)
    ProblemDetail invalido(Erros.Invalido e) {
        return problema(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Erros.Conflito.class)
    ProblemDetail conflito(Erros.Conflito e) {
        return problema(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail grande(MaxUploadSizeExceededException e) {
        return problema(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo passa de 20 MB.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail semArquivo(MissingServletRequestPartException e) {
        return problema(HttpStatus.BAD_REQUEST, "Escolha um arquivo para enviar.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail semCabecalho(MissingRequestHeaderException e) {
        return problema(HttpStatus.BAD_REQUEST, "Cabeçalho " + e.getHeaderName() + " ausente.");
    }

    @ExceptionHandler(Erros.NaoAutenticado.class)
    ResponseEntity<ProblemDetail> naoAutenticado(Erros.NaoAutenticado e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problema(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @ExceptionHandler(Erros.Proibido.class)
    ProblemDetail proibido(Erros.Proibido e) {
        return problema(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(Erros.LimiteExcedido.class)
    ResponseEntity<ProblemDetail> limite(Erros.LimiteExcedido e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.segundos()))
                .body(problema(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ProblemDetail parametroInvalido(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido na requisição.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail tipo(MethodArgumentTypeMismatchException e) {
        return problema(HttpStatus.BAD_REQUEST, "Valor inválido para " + e.getName() + ".");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacao(MethodArgumentNotValidException e) {
        // as mensagens vêm das anotações de validação escritas por nós, nunca do valor enviado
        String detalhe = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage()).filter(Objects::nonNull).distinct().collect(Collectors.joining(" "));
        return problema(HttpStatus.BAD_REQUEST, detalhe.isBlank() ? "Dados inválidos na requisição." : detalhe);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail corpo(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido.");
    }

    @ExceptionHandler(java.io.IOException.class)
    ProblemDetail io(java.io.IOException e) {
        log.error("Erro de leitura/gravação", e);
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Não conseguimos ler o arquivo. Confira se ele abre normalmente no Word.");
    }

    /** Última rede: nada de stack trace ou mensagem interna na resposta. Erros do próprio Spring MVC mantêm seu status. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) throw e; // cabe ao Spring Security
        if (e instanceof ErrorResponse er) {
            ProblemDetail corpo = er.getBody();
            if (er.getStatusCode().is5xxServerError()) corpo.setDetail("Erro inesperado. Tente de novo em instantes.");
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders()).body(corpo);
        }
        log.error("Erro inesperado", e);
        return ResponseEntity.internalServerError()
                .body(problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado. Tente de novo em instantes."));
    }

    private static ProblemDetail problema(HttpStatus status, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(status.getReasonPhrase());
        return p;
    }
}
