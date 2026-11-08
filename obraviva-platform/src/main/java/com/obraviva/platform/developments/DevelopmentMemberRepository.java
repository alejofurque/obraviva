package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DevelopmentMemberRepository extends JpaRepository<DevelopmentMember, UUID> {

    boolean existsByDevelopmentIdAndUserId(UUID developmentId, UUID userId);

    /** Obras asignadas a un encargado, dentro de sus organizaciones. */
    List<DevelopmentMember> findByUserIdAndOrganizationIdIn(UUID userId, Collection<UUID> organizationIds);

    List<DevelopmentMember> findByDevelopmentIdAndOrganizationIdOrderByCreatedAtAsc(UUID developmentId, UUID organizationId);

    Optional<DevelopmentMember> findByDevelopmentIdAndUserIdAndOrganizationId(
            UUID developmentId, UUID userId, UUID organizationId);
}
