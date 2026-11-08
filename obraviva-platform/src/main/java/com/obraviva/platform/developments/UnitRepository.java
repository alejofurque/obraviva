package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitRepository extends JpaRepository<Unit, UUID> {

    List<Unit> findByDevelopmentIdAndOrganizationIdIn(UUID developmentId, Collection<UUID> organizationIds);

    Optional<Unit> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    /**
     * Verifica el código antes de insertar, para devolver 409 en vez de dejar que la
     * restricción de base explote como 500.
     */
    boolean existsByDevelopmentIdAndCode(UUID developmentId, String code);
}