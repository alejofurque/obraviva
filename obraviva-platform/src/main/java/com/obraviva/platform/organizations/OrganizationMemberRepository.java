package com.obraviva.platform.organizations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    List<OrganizationMember> findByOrganizationId(UUID organizationId);

    List<OrganizationMember> findByUserId(UUID userId);

    List<OrganizationMember> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    Optional<OrganizationMember> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}