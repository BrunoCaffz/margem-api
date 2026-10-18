package com.margem.api.repositorio;

import com.margem.api.dominio.Documento;
import com.margem.api.dominio.Enums.StatusDocumento;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentoRepository extends JpaRepository<Documento, UUID> {

    List<Documento> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId, Pageable pageable);

    Optional<Documento> findByIdAndUsuarioId(UUID id, UUID usuarioId);

    @EntityGraph(attributePaths = "orientacoes")
    Optional<Documento> findComOrientacoesByIdAndUsuarioId(UUID id, UUID usuarioId);

    List<Documento> findByStatusIn(List<StatusDocumento> status);
}
