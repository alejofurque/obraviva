package com.obraviva.platform.reports;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    Optional<Report> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    List<Report> findByDevelopmentIdAndOrganizationIdInOrderByPeriodStartDesc(
            UUID developmentId, Collection<UUID> organizationIds);

    /** Bandeja de revisión: los más antiguos primero, que son los que más esperaron. */
    List<Report> findByOrganizationIdInAndStatusOrderByCreatedAtAsc(
            Collection<UUID> organizationIds, ReportStatus status);
}