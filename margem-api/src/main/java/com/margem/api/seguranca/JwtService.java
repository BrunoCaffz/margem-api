package com.margem.api.seguranca;

import com.margem.api.config.SegurancaProperties;
import com.margem.api.dominio.Usuario;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Emite e valida o access token (JWT HS256, curto). Valida assinatura, emissor, audiência e expiração. */
@Component
public class JwtService {

    private final SegurancaProperties props;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtService(SegurancaProperties props) {
        this.props = props;
        byte[] segredo = props.jwtSecret() == null ? new byte[0] : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (segredo.length < 32) {
            throw new IllegalStateException("JWT_SECRET precisa ter pelo menos 32 caracteres. "
                    + "Gere um com: openssl rand -base64 48");
        }
        SecretKey chave = new SecretKeySpec(segredo, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(chave));
        NimbusJwtDecoder dec = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build();
        dec.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.emissor()), audiencia(props.audiencia())));
        this.decoder = dec;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public String emitir(Usuario usuario) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.emissor())
                .audience(List.of(props.audiencia()))
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(agora.plus(props.accessTtl()))
                .id(UUID.randomUUID().toString())
                .claim("papel", usuario.getPapel().name())
                .build();
        JwsHeader cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
    }

    private static OAuth2TokenValidator<Jwt> audiencia(String esperada) {
        OAuth2Error erro = new OAuth2Error("invalid_token", "Audiência inválida.", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(esperada)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(erro);
    }
}
