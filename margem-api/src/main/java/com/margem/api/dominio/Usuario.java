package com.margem.api.dominio;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuario", uniqueConstraints = @UniqueConstraint(name = "uk_usuario_email", columnNames = "email"))
public class Usuario {

    public enum Papel { USER, ADMIN }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 254)
    private String email;

    /** Hash BCrypt. Nunca sai da camada de serviço. */
    @Column(nullable = false, length = 100)
    private String senhaHash;

    @Column(length = 100)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Papel papel = Papel.USER;

    private Instant criadoEm = Instant.now();

    protected Usuario() {
    }

    public Usuario(String email, String senhaHash, String nome) {
        this.email = email;
        this.senhaHash = senhaHash;
        this.nome = nome;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getSenhaHash() { return senhaHash; }
    public String getNome() { return nome; }
    public Papel getPapel() { return papel; }
}
