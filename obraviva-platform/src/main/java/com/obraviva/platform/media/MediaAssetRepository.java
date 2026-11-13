package com.obraviva.platform.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /** La cola de versiones livianas: lo pendiente ya disponible, de lo más viejo a lo más nuevo. */
    List<MediaAsset> findTop5ByVariantsStatusAndStatusOrderByCreatedAtAsc(MediaVariantsStatus variantsStatus, MediaStatus status);

    List<MediaAsset> findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCreatedAtDesc(
            UUID developmentId, Collection<UUID> organizationIds, MediaStatus status);

    Optional<MediaAsset> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    /** Fotos y videos publicados de un sector de una casa: lo que impide quitarlo. */
    /** Cuántas fotos disponibles tienen esas versiones de un avance. */
    long countByOrganizationIdAndProgressEntryIdInAndKindAndStatus(
            UUID organizationId, Collection<UUID> progressEntryIds, MediaKind kind, MediaStatus status);

    long countByOrganizationIdAndUnitIdAndSectorAndStatus(
            UUID organizationId, UUID unitId, String sector, MediaStatus status);

    /** Renombre en cascada de un sector, sólo en esa casa. */
    @Modifying(flushAutomatically = true)
    @Query("update MediaAsset m set m.sector = :to "
            + "where m.organizationId = :organizationId and m.unitId = :unitId and m.sector = :from")
    int renameSector(@Param("organizationId") UUID organizationId, @Param("unitId") UUID unitId,
                     @Param("from") String from, @Param("to") String to);
}