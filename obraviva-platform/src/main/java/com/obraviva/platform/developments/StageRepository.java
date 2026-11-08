package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StageRepository extends JpaRepository<Stage, UUID> {

    List<Stage> findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(
            UUID developmentId, Collection<UUID> organizationIds);

    List<Stage> findByDevelopmentIdInAndOrganizationIdIn(
            Collection<UUID> developmentIds, Collection<UUID> organizationIds);

    Optional<Stage> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    boolean existsByDevelopmentIdAndOrderIndex(UUID developmentId, int orderIndex);

    /** Etapas de una casa, en orden de obra. */
    List<Stage> findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(UUID unitId, Collection<UUID> organizationIds);

    /** Obras comunes: las etapas del desarrollo que no son de ninguna casa. */
    List<Stage> findByDevelopmentIdAndUnitIdIsNullAndOrganizationIdInOrderByOrderIndexAsc(
            UUID developmentId, Collection<UUID> organizationIds);

    boolean existsByUnitIdAndOrderIndex(UUID unitId, int orderIndex);

    boolean existsByDevelopmentIdAndUnitIdIsNullAndOrderIndex(UUID developmentId, int orderIndex);
}