package com.obraviva.platform.captures3d;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Capture3dRepository extends JpaRepository<Capture3d, UUID> {

    /** Capturas disponibles del desarrollo, en orden cronológico (viaje en el tiempo). */
    List<Capture3d> findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCaptureDateAsc(
            UUID developmentId, Collection<UUID> organizationIds, CaptureStatus status);

    /**
     * Capturas de un período. Se filtra por estado porque una captura todavía en
     * proceso no existe para el comprador y no debe aparecer en un reporte.
     */
    List<Capture3d> findByDevelopmentIdAndOrganizationIdInAndStatusAndCaptureDateBetweenOrderByCaptureDateAsc(
            UUID developmentId, Collection<UUID> organizationIds, CaptureStatus status,
            LocalDate from, LocalDate to);

    /** Capturas en un estado dado, de la más reciente a la más antigua (seguimiento de subidas). */
    List<Capture3d> findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCreatedAtDesc(
            UUID developmentId, Collection<UUID> organizationIds, CaptureStatus status);

    Optional<Capture3d> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    /** Recorridos publicados de un sector de una casa: lo que impide quitarlo. */
    long countByOrganizationIdAndUnitIdAndZoneAndStatus(
            UUID organizationId, UUID unitId, String zone, CaptureStatus status);

    /** Renombre en cascada de un sector, sólo en esa casa y en cualquier estado. */
    @Modifying(flushAutomatically = true)
    @Query("update Capture3d c set c.zone = :to "
            + "where c.organizationId = :organizationId and c.unitId = :unitId and c.zone = :from")
    int renameZone(@Param("organizationId") UUID organizationId, @Param("unitId") UUID unitId,
                   @Param("from") String from, @Param("to") String to);

    /** Capturas de una fecha, en cualquier estado: para la regla de un recorrido por sector y día. */
    List<Capture3d> findByDevelopmentIdAndOrganizationIdAndCaptureDate(
            UUID developmentId, UUID organizationId, LocalDate captureDate);
}