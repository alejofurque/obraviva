package com.obraviva.platform.comparisons;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectComparisonRepository extends JpaRepository<ProjectComparison, UUID> {

    List<ProjectComparison> findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtAsc(
            UUID developmentId, Collection<UUID> organizationIds);

    Optional<ProjectComparison> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}
