package com.obraviva.platform.documents;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitDocumentRepository extends JpaRepository<UnitDocument, UUID> {

    List<UnitDocument> findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtDesc(
            UUID developmentId, Collection<UUID> organizationIds);

    List<UnitDocument> findByDevelopmentIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID developmentId, UUID organizationId, UnitDocument.Status status);

    Optional<UnitDocument> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}
