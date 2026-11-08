package com.obraviva.platform.media;

import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * API pública del módulo media hacia otros módulos.
 *
 * <p>Devuelve la clave de almacenamiento y no una URL: quien llama decide con qué
 * vigencia firmarla, porque eso depende de si el contenido va al panel privado o a
 * una vista pública.
 */
@Service
public class MediaCatalogService {

    private final MediaAssetRepository mediaRepository;
    private final StorageService storageService;

    public MediaCatalogService(MediaAssetRepository mediaRepository, StorageService storageService) {
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
    }

    /**
     * Confirma, colgadas de un avance recién creado, fotos que se subieron antes que él.
     *
     * <p>Cada una tiene que ser una foto de la misma obra y la misma casa, subida por la
     * misma persona, sin confirmar, sin avance y con su archivo en el almacenamiento. Hasta
     * que pasa por acá queda en PENDING, y PENDING no aparece en la galería, la historia ni
     * la vista del comprador: una foto sin avance confirmada sería una foto de galería.
     *
     * <p>Corre dentro de la transacción de quien publica: si una foto no cumple, se revierte
     * también el avance. No verifica acceso a la obra; recibe la organización resuelta.
     * {@code sectors} es el sector de cada foto, que quien llama ya validó contra la casa.
     */
    @Transactional
    public void attachPendingPhotos(
            UUID organizationId, UUID developmentId, UUID unitId, UUID uploadedBy,
            UUID progressEntryId, Collection<UUID> photoIds, Map<UUID, String> sectors) {
        for (UUID photoId : new LinkedHashSet<>(photoIds)) {
            MediaAsset asset = mediaRepository.findByIdAndOrganizationIdIn(photoId, List.of(organizationId))
                    .filter(candidate -> candidate.getDevelopmentId().equals(developmentId))
                    .filter(candidate -> Objects.equals(candidate.getUnitId(), unitId))
                    .filter(candidate -> candidate.getCreatedBy().equals(uploadedBy))
                    .filter(candidate -> candidate.getKind() == MediaKind.PHOTO)
                    .filter(candidate -> candidate.getStatus() == MediaStatus.PENDING)
                    .filter(candidate -> candidate.getProgressEntryId() == null)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Una de las fotos no corresponde a este avance; volvé a subirla"));
            if (!storageService.exists(asset.getStorageKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Una de las fotos no terminó de subir; volvé a intentarlo");
            }
            asset.attachToEntry(progressEntryId);
            asset.assignSector(sectors.get(photoId));
            asset.markAvailable();
        }
    }

    /**
     * Cuántas fotos y videos publicados tiene un sector de una casa: lo que el dueño tendría
     * que mover antes de quitarlo.
     */
    @Transactional(readOnly = true)
    public long countInSector(UUID organizationId, UUID unitId, String sector) {
        return mediaRepository.countByOrganizationIdAndUnitIdAndSectorAndStatus(
                organizationId, unitId, sector, MediaStatus.AVAILABLE);
    }

    /** Renombre de un sector en las fotos de esa casa, en la transacción de quien renombra. */
    @Transactional
    public int renameSector(UUID organizationId, UUID unitId, String from, String to) {
        return mediaRepository.renameSector(organizationId, unitId, from, to);
    }

    /**
     * Medio visto desde fuera del módulo. {@code progressEntryId} es la versión del
     * avance a la que se vinculó al subirlo, o nulo si es una foto suelta de la obra.
     */
    public record MediaSummary(
            String storageKey,
            String contentType,
            MediaKind kind,
            Instant createdAt,
            UUID progressEntryId,
            UUID id,
            UUID unitId,
            String sector,
            boolean showcase,
            java.time.LocalDate takenOn,
            /** Las versiones livianas; nulas mientras no estén: se usa el original. */
            String thumbStorageKey,
            String screenStorageKey) {
    }

    /**
     * Medios disponibles de un desarrollo, los más nuevos primero.
     *
     * <p>Sólo los que el backend verificó en el almacenamiento: un medio cuya subida
     * nunca se completó queda en PENDING y no tiene archivo detrás, así que produciría
     * una imagen rota.
     */
    @Transactional(readOnly = true)
    public List<MediaSummary> availableMedia(UUID developmentId, List<UUID> organizationIds) {
        return mediaRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCreatedAtDesc(
                        developmentId, organizationIds, MediaStatus.AVAILABLE)
                .stream()
                .map(asset -> new MediaSummary(
                        asset.getStorageKey(),
                        asset.getContentType(),
                        asset.getKind(),
                        asset.getCreatedAt(),
                        asset.getProgressEntryId(),
                        asset.getId(),
                        asset.getUnitId(),
                        asset.getSector(),
                        asset.isShowcase(),
                        asset.getTakenOn(),
                        asset.getThumbStorageKey(),
                        asset.getScreenStorageKey()))
                .toList();
    }

    /** Fotos disponibles de la obra, las más nuevas primero: lo que se puede comparar con un render. */
    @Transactional(readOnly = true)
    public List<MediaSummary> availablePhotos(UUID developmentId, List<UUID> organizationIds) {
        return availableMedia(developmentId, organizationIds).stream()
                .filter(media -> media.kind() == MediaKind.PHOTO)
                .toList();
    }
}