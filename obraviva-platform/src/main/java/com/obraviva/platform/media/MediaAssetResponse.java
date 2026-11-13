package com.obraviva.platform.media;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Medio de la obra. {@code progressRootId} es el avance al que pertenece aunque se haya
 * corregido después de subir la foto; {@code mine} dice si lo subió quien consulta, que
 * es lo que decide si un encargado puede borrarlo.
 */
public record MediaAssetResponse(
        UUID id,
        UUID developmentId,
        UUID progressEntryId,
        UUID progressRootId,
        UUID unitId,
        String originalFilename,
        String contentType,
        MediaKind kind,
        MediaStatus status,
        /** El original; nulo si todavía no se confirmó. */
        String downloadUrl,
        /** Miniatura (480 px) y pantalla (1600 px), nulas mientras no estén: se usa el original. */
        String thumbUrl,
        String screenUrl,
        boolean mine,
        Instant createdAt,
        /** Lugar de la casa; nulo: "General". */
        String sector,
        /** De la galería de venta, no evidencia de obra. */
        boolean showcase,
        /** Día de la sesión en que se sacó; nulo si no viene de una sesión. */
        LocalDate takenOn) {

    static MediaAssetResponse from(MediaAsset asset, UnaryOperator<String> sign, UUID progressRootId, UUID viewerId) {
        String downloadUrl = asset.isAvailable() ? sign.apply(asset.getStorageKey()) : null;
        return new MediaAssetResponse(
                asset.getId(),
                asset.getDevelopmentId(),
                asset.getProgressEntryId(),
                progressRootId,
                asset.getUnitId(),
                asset.getOriginalFilename(),
                asset.getContentType(),
                asset.getKind(),
                asset.getStatus(),
                downloadUrl,
                asset.getThumbStorageKey() == null ? null : sign.apply(asset.getThumbStorageKey()),
                asset.getScreenStorageKey() == null ? null : sign.apply(asset.getScreenStorageKey()),
                asset.getCreatedBy().equals(viewerId),
                asset.getCreatedAt(),
                asset.getSector(),
                asset.isShowcase(),
                asset.getTakenOn());
    }
}