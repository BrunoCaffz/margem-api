package com.margem.api.web;

import com.margem.api.servico.Erros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
    ProblemDetail semCliente(MissingRequestHeaderException e) {
        return problema(HttpStatus.BAD_REQUEST, "Cabeçalho " + e.getHeaderName() + " ausente.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail tipo(MethodArgumentTypeMismatchException e) {
        return problema(HttpStatus.BAD_REQUEST, "Valor inválido para " + e.getName() + ".");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacao(MethodArgumentNotValidException e) {
        return problema(HttpStatus.BAD_REQUEST, "Dados inválidos na requisição.");
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

    private static ProblemDetail problema(HttpStatus status, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(status.getReasonPhrase());
        return p;
    }
}
