package com.obraviva.platform.comparisons;

import com.obraviva.platform.comparisons.ComparisonDtos.ComparisonResponse;
import com.obraviva.platform.comparisons.ComparisonDtos.CreateComparisonRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.RenderUploadResponse;
import com.obraviva.platform.comparisons.ComparisonDtos.RequestRenderUploadRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.UpdateComparisonRequest;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.media.MediaCatalogService;
import com.obraviva.platform.media.MediaCatalogService.MediaSummary;
import com.obraviva.platform.media.variants.ImageVariants;
import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Comparador proyecto/realidad. Lo arma el dueño: es contenido comercial, igual que la
 * ficha y la portada.
 */
@Service
public class ProjectComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ProjectComparisonService.class);

    /** Un render se muestra en el navegador tal cual: no hace falta leerlo en el servidor. */
    private static final Map<String, String> RENDER_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final ProjectComparisonRepository comparisonRepository;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final UnitCatalogService unitCatalog;
    private final MediaCatalogService mediaCatalog;
    private final StorageService storageService;
    private final ImageVariants imageVariants;

    public ProjectComparisonService(
            ProjectComparisonRepository comparisonRepository,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            UnitCatalogService unitCatalog,
            MediaCatalogService mediaCatalog,
            StorageService storageService,
            ImageVariants imageVariants) {
        this.imageVariants = imageVariants;
        this.comparisonRepository = comparisonRepository;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.unitCatalog = unitCatalog;
        this.mediaCatalog = mediaCatalog;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public List<ComparisonResponse> list(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        Map<UUID, MediaSummary> photos = photosOf(developmentId, organizationId);
        List<ProjectComparison> comparisons = comparisonRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtAsc(developmentId, List.of(organizationId));
        // Los renders con sus versiones livianas, en una sola consulta.
        ImageVariants.Images renders = imageVariants.lookup(List.of(organizationId),
                comparisons.stream().map(ProjectComparison::getRenderStorageKey).toList());
        return comparisons.stream()
                .map(comparison -> toResponse(comparison, photos, renders))
                .toList();
    }

    @Transactional
    public ComparisonResponse create(UUID userId, UUID developmentId, CreateComparisonRequest request) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        requireUnitOf(request.unitId(), developmentId, organizationId);
        ProjectComparison comparison = comparisonRepository.save(new ProjectComparison(
                organizationId, developmentId, request.unitId(), request.title().trim(), userId));
        return toResponse(comparison, Map.of());
    }

    @Transactional
    public ComparisonResponse update(UUID userId, UUID comparisonId, UpdateComparisonRequest request) {
        ProjectComparison comparison = requireOwned(userId, comparisonId);
        requireUnitOf(request.unitId(), comparison.getDevelopmentId(), comparison.getOrganizationId());
        Map<UUID, MediaSummary> photos = photosOf(comparison.getDevelopmentId(), comparison.getOrganizationId());
        if (request.photoMediaId() != null && !photos.containsKey(request.photoMediaId())) {
            // Lo que llega del navegador no es confiable: la foto tiene que ser de esta obra.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La foto elegida no es una foto de avance de esta obra");
        }
        comparison.update(request.title().trim(), request.unitId(), request.photoMediaId());
        return toResponse(comparison, photos);
    }

    @Transactional
    public RenderUploadResponse requestRenderUpload(UUID userId, UUID comparisonId, RequestRenderUploadRequest request) {
        String extension = RENDER_TYPES.get(request.contentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El render tiene que ser una imagen JPG, PNG o WebP");
        }
        ProjectComparison comparison = requireOwned(userId, comparisonId);
        String key = comparison.prepareRenderUpload(extension);
        return new RenderUploadResponse(storageService.presignUpload(key, request.contentType()), request.contentType());
    }

    @Transactional
    public ComparisonResponse confirmRender(UUID userId, UUID comparisonId) {
        ProjectComparison comparison = requireOwned(userId, comparisonId);
        String pending = comparison.getRenderPendingStorageKey();
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay un render pendiente de confirmar");
        }
        if (!storageService.exists(pending)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El render no se encuentra en el almacenamiento");
        }
        renderReplaced(comparison, comparison.confirmRender());
        log.info("Render de la comparación {} actualizado por {}", comparisonId, userId);
        return toResponse(comparison, photosOf(comparison.getDevelopmentId(), comparison.getOrganizationId()));
    }

    /** Render desde un archivo que ya está en el servidor, para los datos de demostración. */
    @Transactional
    public ComparisonResponse importRender(UUID userId, UUID comparisonId, java.nio.file.Path file, String contentType) {
        String extension = RENDER_TYPES.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Tipo de render no aceptado: " + contentType);
        }
        ProjectComparison comparison = requireOwned(userId, comparisonId);
        String key = comparison.prepareRenderUpload(extension);
        storageService.upload(file, key, contentType);
        renderReplaced(comparison, comparison.confirmRender());
        return toResponse(comparison, photosOf(comparison.getDevelopmentId(), comparison.getOrganizationId()));
    }

    @Transactional
    public void delete(UUID userId, UUID comparisonId) {
        ProjectComparison comparison = requireOwned(userId, comparisonId);
        imageVariants.discard(comparison.getOrganizationId(), comparison.getRenderStorageKey());
        comparisonRepository.delete(comparison);
    }

    /** El render anterior deja de tener versiones livianas (el original queda) y el nuevo entra a la cola. */
    private void renderReplaced(ProjectComparison comparison, String previous) {
        imageVariants.discard(comparison.getOrganizationId(), previous);
        imageVariants.enqueue(comparison.getOrganizationId(), comparison.getRenderStorageKey());
    }

    private ProjectComparison requireOwned(UUID userId, UUID comparisonId) {
        ProjectComparison comparison = comparisonRepository
                .findByIdAndOrganizationIdIn(comparisonId, membershipService.organizationIdsOf(userId))
                .filter(found -> developmentAccess.canAccess(userId, found.getDevelopmentId(), found.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparación no encontrada"));
        if (!membershipService.isOwnerOf(userId, comparison.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return comparison;
    }

    private void requireUnitOf(UUID unitId, UUID developmentId, UUID organizationId) {
        if (unitId == null) {
            return;
        }
        boolean belongs = unitCatalog.findInOrganization(unitId, organizationId)
                .filter(unit -> unit.developmentId().equals(developmentId))
                .isPresent();
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }
    }

    private Map<UUID, MediaSummary> photosOf(UUID developmentId, UUID organizationId) {
        return mediaCatalog.availablePhotos(developmentId, List.of(organizationId)).stream()
                .collect(Collectors.toMap(MediaSummary::id, Function.identity()));
    }

    private ComparisonResponse toResponse(ProjectComparison comparison, Map<UUID, MediaSummary> photos) {
        return toResponse(comparison, photos, imageVariants.lookup(List.of(comparison.getOrganizationId()),
                java.util.Collections.singletonList(comparison.getRenderStorageKey())));
    }

    private ComparisonResponse toResponse(ProjectComparison comparison, Map<UUID, MediaSummary> photos, ImageVariants.Images renders) {
        MediaSummary photo = comparison.getPhotoMediaId() == null ? null : photos.get(comparison.getPhotoMediaId());
        SignedImage render = renders.sign(comparison.getRenderStorageKey());
        return new ComparisonResponse(
                comparison.getId(),
                comparison.getDevelopmentId(),
                comparison.getUnitId(),
                comparison.getTitle(),
                render == null ? null : render.url(),
                render == null ? null : render.thumbUrl(),
                render == null ? null : render.screenUrl(),
                comparison.getPhotoMediaId(),
                photo == null ? null : storageService.presignDownload(photo.storageKey()),
                comparison.isComplete() && photo != null,
                comparison.getCreatedAt());
    }
}
