package com.margem.api.seguranca;

import com.margem.api.config.SegurancaProperties;
import com.margem.api.dominio.RefreshToken;
import com.margem.api.dominio.Usuario;
import com.margem.api.repositorio.RefreshTokenRepository;
import com.margem.api.repositorio.UsuarioRepository;
import com.margem.api.servico.Erros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String CREDENCIAIS_INVALIDAS = "E-mail ou senha incorretos.";
    private static final String SESSAO_INVALIDA = "Sessão inválida ou expirada. Entre de novo.";
    private static final String CADASTRO_RECUSADO = "Não foi possível criar a conta com esses dados.";

    public record Sessao(String accessToken, String refreshToken, Usuario usuario) {
    }

    private final UsuarioRepository usuarios;
    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwt;
    private final LimitadorRequisicoes limitador;
    private final SegurancaProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom aleatorio = new SecureRandom();
    /** Hash descartável: login de e-mail inexistente gasta o mesmo tempo de um e-mail existente. */
    private final String hashFalso = encoder.encode(UUID.randomUUID().toString());

    public AuthService(UsuarioRepository usuarios, RefreshTokenRepository refreshTokens, JwtService jwt,
                       LimitadorRequisicoes limitador, SegurancaProperties props) {
        this.usuarios = usuarios;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.limitador = limitador;
        this.props = props;
    }

    @Transactional
    public Sessao cadastrar(String email, String senha, String nome) {
        String normalizado = normalizar(email);
        exigirSenhaCabeNoBcrypt(senha);
        String nomeLimpo = nome == null || nome.isBlank() ? null : nome.trim();
        if (usuarios.existsByEmail(normalizado)) throw new Erros.Conflito(CADASTRO_RECUSADO);
        try {
            Usuario u = usuarios.saveAndFlush(new Usuario(normalizado, encoder.encode(senha), nomeLimpo));
            return novaSessao(u);
        } catch (DataIntegrityViolationException e) { // dois cadastros simultâneos com o mesmo e-mail
            throw new Erros.Conflito(CADASTRO_RECUSADO);
        }
    }

    @Transactional
    public Sessao entrar(String email, String senha) {
        String normalizado = normalizar(email);
        long espera = limitador.consumir("login:" + normalizado, props.limites().loginEmail());
        if (espera > 0) throw new Erros.LimiteExcedido(espera);

        Usuario u = usuarios.findByEmail(normalizado).orElse(null);
        boolean ok = encoder.matches(senha, u != null ? u.getSenhaHash() : hashFalso);
        if (u == null || !ok) throw new Erros.NaoAutenticado(CREDENCIAIS_INVALIDAS);
        return novaSessao(u);
    }

    /**
     * Troca o refresh token por um par novo (rotação). Se um token já usado voltar, alguém o copiou:
     * todas as sessões da conta são encerradas. O noRollbackFor mantém essa revogação gravada.
     */
    @Transactional(noRollbackFor = Erros.NaoAutenticado.class)
    public Sessao renovar(String refreshBruto) {
        RefreshToken atual = buscar(refreshBruto);
        if (atual.isRevogado()) {
            refreshTokens.revogarTodos(atual.getUsuarioId(), Instant.now());
            log.warn("Reuso de refresh token detectado; sessões do usuário {} revogadas", atual.getUsuarioId());
            throw new Erros.NaoAutenticado(SESSAO_INVALIDA);
        }
        if (atual.getExpiraEm().isBefore(Instant.now())) throw new Erros.NaoAutenticado(SESSAO_INVALIDA);
        Usuario u = usuarios.findById(atual.getUsuarioId()).orElseThrow(() -> new Erros.NaoAutenticado(SESSAO_INVALIDA));
        atual.revogar();
        return novaSessao(u);
    }

    @Transactional
    public void sair(String refreshBruto) {
        if (refreshBruto == null || refreshBruto.isBlank() || refreshBruto.length() > 100) return;
        refreshTokens.findByHash(hash(refreshBruto)).ifPresent(RefreshToken::revogar);
    }

    @Transactional(readOnly = true)
    public Usuario usuario(UUID id) {
        return usuarios.findById(id).orElseThrow(() -> new Erros.NaoAutenticado(SESSAO_INVALIDA));
    }

    /** Limpa tokens vencidos há mais de um dia. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void limparExpirados() {
        int n = refreshTokens.apagarExpirados(Instant.now().minusSeconds(86_400));
        if (n > 0) log.info("{} refresh tokens expirados apagados", n);
    }

    // ---------------------------------------------------------------- apoio

    private Sessao novaSessao(Usuario u) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(u.getId(), hash(refresh), Instant.now().plus(props.refreshTtl())));
        return new Sessao(jwt.emitir(u), refresh, u);
    }

    private RefreshToken buscar(String bruto) {
        if (bruto == null || bruto.isBlank() || bruto.length() > 100) throw new Erros.NaoAutenticado(SESSAO_INVALIDA);
        return refreshTokens.findByHash(hash(bruto)).orElseThrow(() -> new Erros.NaoAutenticado(SESSAO_INVALIDA));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** O BCrypt ignora o que passa de 72 bytes; recusar evita senha "truncada" sem o usuário saber. */
    private static void exigirSenhaCabeNoBcrypt(String senha) {
        if (senha.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new Erros.Invalido("A senha pode ter no máximo 72 bytes (acentos contam como 2).");
        }
    }
}
