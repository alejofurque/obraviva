package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UnitSupervisorRepository extends JpaRepository<UnitSupervisor, UUID> {

    boolean existsByDevelopmentIdAndUserId(UUID developmentId, UUID userId);

    boolean existsByUnitIdAndUserId(UUID unitId, UUID userId);

    List<UnitSupervisor> findByDevelopmentIdAndUserId(UUID developmentId, UUID userId);

    List<UnitSupervisor> findByUserIdAndOrganizationIdIn(UUID userId, Collection<UUID> organizationIds);

    List<UnitSupervisor> findByDevelopmentIdAndOrganizationIdOrderByCreatedAtAsc(UUID developmentId, UUID organizationId);

    void deleteByDevelopmentIdAndUserId(UUID developmentId, UUID userId);
}
