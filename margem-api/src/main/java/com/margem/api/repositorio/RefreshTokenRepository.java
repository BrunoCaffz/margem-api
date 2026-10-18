package com.margem.api.repositorio;

import com.margem.api.dominio.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByHash(String hash);

    @Modifying
    @Query("update RefreshToken t set t.revogadoEm = :agora where t.usuarioId = :usuarioId and t.revogadoEm is null")
    int revogarTodos(UUID usuarioId, Instant agora);

    @Modifying
    @Query("delete from RefreshToken t where t.expiraEm < :limite")
    int apagarExpirados(Instant limite);
}
