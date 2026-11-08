package com.obraviva.platform.plans;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FloorPlanRepository extends JpaRepository<FloorPlan, UUID> {

    List<FloorPlan> findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(
            UUID unitId, Collection<UUID> organizationIds);

    Optional<FloorPlan> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}
