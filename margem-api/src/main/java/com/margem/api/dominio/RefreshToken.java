package com.margem.api.dominio;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Só o hash SHA-256 do token fica no banco: um vazamento da tabela não entrega sessões válidas. */
@Entity
@Table(name = "refresh_token", indexes = {
        @Index(name = "idx_refresh_usuario", columnList = "usuario_id"),
        @Index(name = "idx_refresh_expira", columnList = "expira_em")},
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_hash", columnNames = "hash"))
public class RefreshToken {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID usuarioId;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(nullable = false)
    private Instant expiraEm;

    private Instant revogadoEm;

    protected RefreshToken() {
    }

    public RefreshToken(UUID usuarioId, String hash, Instant expiraEm) {
        this.usuarioId = usuarioId;
        this.hash = hash;
        this.expiraEm = expiraEm;
    }

    public UUID getUsuarioId() { return usuarioId; }
    public Instant getExpiraEm() { return expiraEm; }
    public boolean isRevogado() { return revogadoEm != null; }
    public void revogar() { if (revogadoEm == null) revogadoEm = Instant.now(); }
}
