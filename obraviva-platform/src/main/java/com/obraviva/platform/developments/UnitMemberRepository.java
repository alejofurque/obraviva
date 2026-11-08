package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitMemberRepository extends JpaRepository<UnitMember, UUID> {

    /**
     * Unidades de un comprador. Sin acotar por organización a propósito: el comprador
     * no pertenece a ninguna, y el propio vínculo es el alcance.
     */
    List<UnitMember> findByUserId(UUID userId);

    boolean existsByUnitIdAndUserId(UUID unitId, UUID userId);

    /** Miembros de una unidad, para el panel del desarrollista. */
    List<UnitMember> findByUnitIdAndOrganizationIdInOrderByCreatedAtAsc(
            UUID unitId, Collection<UUID> organizationIds);

    /** Compradores de varias unidades en una consulta, para contar por unidad. */
    List<UnitMember> findByUnitIdInAndOrganizationIdIn(
            Collection<UUID> unitIds, Collection<UUID> organizationIds);

    Optional<UnitMember> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}
