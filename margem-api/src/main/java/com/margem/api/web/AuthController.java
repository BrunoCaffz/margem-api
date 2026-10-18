package com.margem.api.web;

import com.margem.api.config.SegurancaProperties;
import com.margem.api.dominio.Usuario;
import com.margem.api.seguranca.AuthService;
import com.margem.api.servico.Erros;
import com.margem.api.web.dto.Requisicoes;
import com.margem.api.web.dto.Respostas.Sessao;
import com.margem.api.web.dto.Respostas.UsuarioDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Access token (JWT, curto) vai no corpo e o front o guarda só em memória.
 * O refresh token vai num cookie HttpOnly restrito a /api/auth, que o JavaScript não lê.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação", description = "Cadastro, login, renovação e logout")
public class AuthController {

    static final String COOKIE = "margem_refresh";
    /** Cabeçalho que o front manda em /refresh e /logout. Um formulário de outro site não consegue enviá-lo. */
    public static final String CABECALHO_CSRF = "X-Margem-Csrf";

    private final AuthService auth;
    private final SegurancaProperties props;

    public AuthController(AuthService auth, SegurancaProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/cadastro")
    @SecurityRequirements
    @Operation(summary = "Cria a conta e já entra", description = "Senha de 10 a 72 caracteres. Papel sempre USER.")
    public ResponseEntity<Sessao> cadastrar(@Valid @RequestBody Requisicoes.Cadastro corpo) {
        return resposta(HttpStatus.CREATED, auth.cadastrar(corpo.email(), corpo.senha(), corpo.nome()));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Entra com e-mail e senha",
            description = "Devolve o access token no corpo e define o cookie HttpOnly de renovação.")
    public ResponseEntity<Sessao> entrar(@Valid @RequestBody Requisicoes.Login corpo) {
        return resposta(HttpStatus.OK, auth.entrar(corpo.email(), corpo.senha()));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Renova o access token",
            description = "Usa o cookie de renovação (que gira a cada uso). Exige o cabeçalho X-Margem-Csrf.")
    public ResponseEntity<Sessao> renovar(@CookieValue(name = COOKIE, required = false) String refresh,
                                          @RequestHeader(name = CABECALHO_CSRF, required = false) String csrf) {
        exigirCsrf(csrf);
        return resposta(HttpStatus.OK, auth.renovar(refresh));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Encerra a sessão", description = "Revoga o refresh token e apaga o cookie. Exige X-Margem-Csrf.")
    public ResponseEntity<Void> sair(@CookieValue(name = COOKIE, required = false) String refresh,
                                     @RequestHeader(name = CABECALHO_CSRF, required = false) String csrf) {
        exigirCsrf(csrf);
        auth.sair(refresh);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/me")
    @Operation(summary = "Dados de quem está logado")
    public UsuarioDto eu(Authentication autenticacao) {
        return dto(auth.usuario(UUID.fromString(autenticacao.getName())));
    }

    // ---------------------------------------------------------------- apoio

    private ResponseEntity<Sessao> resposta(HttpStatus status, AuthService.Sessao s) {
        Sessao corpo = new Sessao(s.accessToken(), "Bearer", props.accessTtl().toSeconds(), dto(s.usuario()));
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), props.refreshTtl()).toString())
                .body(corpo);
    }

    private ResponseCookie cookie(String valor, Duration validade) {
        return ResponseCookie.from(COOKIE, valor)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite(props.cookieSameSite())
                .path("/api/auth")
                .maxAge(validade)
                .build();
    }

    private static void exigirCsrf(String csrf) {
        if (csrf == null || csrf.isBlank()) throw new Erros.Proibido("Requisição não permitida.");
    }

    private static UsuarioDto dto(Usuario u) {
        return new UsuarioDto(u.getId(), u.getEmail(), u.getNome());
    }
}
