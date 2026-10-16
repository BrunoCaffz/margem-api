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

    List<Documento> findByClienteIdOrderByCriadoEmDesc(String clienteId, Pageable pageable);

    Optional<Documento> findByIdAndClienteId(UUID id, String clienteId);

    @EntityGraph(attributePaths = "orientacoes")
    Optional<Documento> findComOrientacoesByIdAndClienteId(UUID id, String clienteId);

    List<Documento> findByStatusIn(List<StatusDocumento> status);
}
