package com.obraviva.platform.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProgressEntryRepository extends JpaRepository<ProgressEntry, UUID> {

    /** Línea de tiempo: solo versiones vigentes, más recientes primero. */
    List<ProgressEntry> findByDevelopmentIdAndOrganizationIdInAndSupersededAtIsNullOrderByEntryDateDesc(
            UUID developmentId, Collection<UUID> organizationIds);

    /**
     * Avances vigentes de un período, en orden cronológico. Sólo las versiones
     * vigentes: un reporte narra lo que hoy se sostiene, no las correcciones que
     * quedaron atrás.
     */
    List<ProgressEntry> findByDevelopmentIdAndOrganizationIdInAndSupersededAtIsNullAndEntryDateBetweenOrderByEntryDateAsc(
            UUID developmentId, Collection<UUID> organizationIds, LocalDate from, LocalDate to);

    /** Historial completo de un avance, todas sus versiones. */
    List<ProgressEntry> findByRootIdAndOrganizationIdInOrderByVersionAsc(
            UUID rootId, Collection<UUID> organizationIds);

    Optional<ProgressEntry> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    /** Todas las versiones, vigentes y superadas, de los avances de un desarrollo. */
    List<ProgressEntry> findByDevelopmentIdAndOrganizationIdIn(
            UUID developmentId, Collection<UUID> organizationIds);
}