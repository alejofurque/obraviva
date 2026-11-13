package com.obraviva.platform.media;

import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.progress.ProgressService;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaAssetRepository mediaRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final ProgressService progressService;
    private final UnitCatalogService unitCatalog;
    private final CaptureSectorService sectorService;

    public MediaService(
            MediaAssetRepository mediaRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            ProgressService progressService,
            UnitCatalogService unitCatalog,
            CaptureSectorService sectorService) {
        this.sectorService = sectorService;
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.progressService = progressService;
        this.unitCatalog = unitCatalog;
    }

    /** Fotos por avance: el mismo tope que la publicación (`@Size` en el pedido) y que la interfaz. */
    static final int MAX_PHOTOS_PER_ENTRY = 10;

    @Transactional
    public UploadUrlResponse requestUploadUrl(
            UUID userId, UUID developmentId, RequestUploadRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        MediaKind kind = resolveKind(request.contentType());
        if (request.progressEntryId() != null
                && progressService.rootOf(developmentId, organizationId, request.progressEntryId()).isEmpty()) {
            // Lo que llega del navegador no es confiable: sin esto, una foto podría
            // colgarse de un avance de otra obra.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El avance no pertenece a esta obra");
        }
        // La foto de un avance es de la casa del avance; si no, de la casa indicada.
        UUID unitId = request.progressEntryId() != null
                ? progressService.unitOf(developmentId, organizationId, request.progressEntryId()).orElse(null)
                : request.unitId();
        if (request.progressEntryId() == null && unitId != null && unitCatalog.findInOrganization(unitId, organizationId)
                .filter(unit -> unit.developmentId().equals(developmentId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, unitId);
        String sector = sectorService.requireSectorOf(unitId, request.sector());
        boolean showcase = request.showcase() != null
                ? request.showcase()
                : request.progressEntryId() == null && unitId != null;
        if (showcase && (unitId == null || request.progressEntryId() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La galería es de una casa y no de un avance");
        }
        if (request.takenOn() != null && request.takenOn().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de la foto no puede ser futura");
        }
        requireRoomForPhoto(organizationId, developmentId, request.progressEntryId(), kind);

        MediaAsset created = new MediaAsset(
                organizationId,
                developmentId,
                request.progressEntryId(),
                request.filename(),
                request.contentType(),
                kind,
                userId);
        created.assignUnit(unitId);
        created.assignSector(sector);
        created.classify(showcase, request.takenOn());
        MediaAsset asset = mediaRepository.save(created);

        String uploadUrl = storageService.presignUpload(asset.getStorageKey(), request.contentType());
        return new UploadUrlResponse(asset.getId(), uploadUrl, request.contentType());
    }

    /**
     * Un avance que ya existe admite hasta {@value #MAX_PHOTOS_PER_ENTRY} fotos, contando las de
     * todas sus versiones ("Agregar fotos" sube de a tandas y no pasa por la publicación). Los
     * videos no cuentan. Si ya está lleno, 400 y no se guarda nada.
     */
    private void requireRoomForPhoto(UUID organizationId, UUID developmentId, UUID entryId, MediaKind kind) {
        if (entryId == null || kind != MediaKind.PHOTO) {
            return;
        }
        UUID root = progressService.rootOf(developmentId, organizationId, entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "El avance no pertenece a esta obra"));
        long photos = mediaRepository.countByOrganizationIdAndProgressEntryIdInAndKindAndStatus(
                organizationId, progressService.versionIdsOf(organizationId, root), MediaKind.PHOTO, MediaStatus.AVAILABLE);
        if (photos >= MAX_PHOTOS_PER_ENTRY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El avance ya tiene " + MAX_PHOTOS_PER_ENTRY + " fotos, el máximo");
        }
    }

    /**
     * Confirma la subida verificando contra el almacenamiento. No se
     * confía en la palabra del cliente: si el objeto no está, falla.
     */
    @Transactional
    public MediaAssetResponse confirmUpload(UUID userId, UUID mediaId) {
        MediaAsset asset = requireWorkable(userId, mediaId);

        if (!storageService.exists(asset.getStorageKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El archivo no se encuentra en el almacenamiento");
        }

        // Otra vez al confirmar: dos subidas pudieron arrancar con el avance en 9 fotos.
        if (asset.getStatus() == MediaStatus.PENDING) {
            requireRoomForPhoto(asset.getOrganizationId(), asset.getDevelopmentId(), asset.getProgressEntryId(), asset.getKind());
        }
        asset.markAvailable();
        UUID root = asset.getProgressEntryId() == null ? null
                : progressService.rootOf(asset.getDevelopmentId(), asset.getOrganizationId(), asset.getProgressEntryId())
                        .orElse(null);
        return MediaAssetResponse.from(asset, storageService::presignDownload, root, userId);
    }

    /**
     * Publica un archivo que ya está en el servidor, sin pasar por la subida del navegador.
     * Lo usan los datos de demostración de la instalación; aplica las mismas validaciones
     * de acceso, tipo y avance que la subida normal.
     */
    @Transactional
    public UUID importFile(
            UUID userId, UUID developmentId, UUID progressEntryId,
            String filename, String contentType, Path file) {
        return importFile(userId, developmentId, progressEntryId, null, filename, contentType, file);
    }

    /** Con {@code unitId} y sin avance, la foto va a la galería de la casa. */
    @Transactional
    public UUID importFile(
            UUID userId, UUID developmentId, UUID progressEntryId, UUID unitId,
            String filename, String contentType, Path file) {
        UploadUrlResponse upload = requestUploadUrl(
                userId, developmentId, new RequestUploadRequest(filename, contentType, progressEntryId, unitId));
        MediaAsset asset = mediaRepository.findById(upload.mediaId()).orElseThrow();
        storageService.upload(file, asset.getStorageKey(), contentType);
        asset.markAvailable();
        return asset.getId();
    }

    @Transactional(readOnly = true)
    public List<MediaAssetResponse> listAvailable(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        Predicate<UUID> readable = developmentAccess.readableUnits(userId, developmentId, organizationId);
        Map<UUID, UUID> roots = progressService.rootIdsByEntryId(developmentId, List.of(organizationId));
        return mediaRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCreatedAtDesc(
                        developmentId, membershipService.organizationIdsOf(userId), MediaStatus.AVAILABLE)
                .stream()
                .filter(asset -> readable.test(asset.getUnitId()))
                .map(asset -> MediaAssetResponse.from(
                        asset,
                        storageService::presignDownload,
                        asset.getProgressEntryId() == null ? null : roots.get(asset.getProgressEntryId()),
                        userId))
                .toList();
    }

    /**
     * Borra una foto o video publicado. El dueño puede con cualquiera; el encargado sólo
     * con lo que subió él, porque borrar el trabajo de otro es una decisión de quien
     * responde por la obra.
     *
     * <p>El archivo se borra después de la fila y sin transacción que lo proteja: si ese
     * borrado falla queda un archivo huérfano, que no se ve en ningún lado, en vez de una
     * imagen rota en la historia.
     */
    @Transactional
    public void delete(UUID userId, UUID mediaId) {
        MediaAsset asset = requireWorkable(userId, mediaId);
        boolean owner = membershipService.isOwnerOf(userId, asset.getOrganizationId());
        if (!owner && !asset.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Sólo el dueño de la obra puede borrar lo que subió otra persona");
        }
        mediaRepository.delete(asset);
        // El original y sus versiones livianas: un archivo que queda huérfano no rompe nada.
        for (String key : new String[] {asset.getStorageKey(), asset.getThumbStorageKey(), asset.getScreenStorageKey()}) {
            if (key == null) {
                continue;
            }
            try {
                storageService.delete(key);
            } catch (RuntimeException e) {
                log.warn("No se pudo borrar el archivo {} del medio {}", key, mediaId, e);
            }
        }
        log.info("Medio {} borrado por {}", mediaId, userId);
    }

    /**
     * Cambia el lugar de la casa de una foto ya subida: lo hace el dueño o el encargado
     * de esa casa. Nulo la pasa a "General". El sector tiene que ser de la casa de la foto,
     * y una foto de obras comunes no lleva sector.
     */
    @Transactional
    public MediaAssetResponse updateSector(UUID userId, UUID mediaId, String sector) {
        MediaAsset asset = requireWorkable(userId, mediaId);
        asset.assignSector(sectorService.requireSectorOf(asset.getUnitId(), sector));
        UUID root = asset.getProgressEntryId() == null ? null
                : progressService.rootOf(asset.getDevelopmentId(), asset.getOrganizationId(), asset.getProgressEntryId())
                        .orElse(null);
        return MediaAssetResponse.from(asset, storageService::presignDownload, root, userId);
    }

    /**
     * "Mover a fotos de obra": una imagen de la galería de venta que en realidad es de la obra
     * pasa a ser foto de un lugar de la casa, con el día en que se sacó. Sale de la galería y
     * entra en "lugar por lugar" como cualquier foto de sesión. Es del dueño, como la galería.
     */
    @Transactional
    public MediaAssetResponse moveToWork(UUID userId, UUID mediaId, String sector, LocalDate takenOn) {
        MediaAsset asset = requireAccessible(userId, mediaId);
        if (!membershipService.isOwnerOf(userId, asset.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        if (!asset.isShowcase() || asset.getUnitId() == null || asset.getProgressEntryId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sólo se pasan a la obra las imágenes de la galería de una casa");
        }
        if (sector == null || sector.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elegí el lugar de la casa donde se sacó la foto");
        }
        if (takenOn == null || takenOn.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de la foto es obligatoria y no puede ser futura");
        }
        asset.assignSector(sectorService.requireSectorOf(asset.getUnitId(), sector));
        asset.classify(false, takenOn);
        return MediaAssetResponse.from(asset, storageService::presignDownload, null, userId);
    }

    /**
     * Para lo que se hace sobre la foto de una casa (confirmarla, borrarla, cambiarle el
     * sector): llegar al desarrollo no alcanza. Un encargado de casas puntuales recibe 403
     * en las demás y en las obras comunes, como en el resto de las cargas.
     */
    private MediaAsset requireWorkable(UUID userId, UUID mediaId) {
        MediaAsset asset = requireAccessible(userId, mediaId);
        if (!developmentAccess.canWorkOnUnit(userId, asset.getDevelopmentId(), asset.getOrganizationId(), asset.getUnitId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, asset.getUnitId() == null
                    ? "Las obras comunes las carga un encargado de todo el desarrollo"
                    : "No estás asignado a esta casa");
        }
        return asset;
    }

    private MediaAsset requireAccessible(UUID userId, UUID mediaId) {
        return mediaRepository
                .findByIdAndOrganizationIdIn(mediaId, membershipService.organizationIdsOf(userId))
                .filter(asset -> developmentAccess.canAccess(userId, asset.getDevelopmentId(), asset.getOrganizationId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado"));
    }

    /** Solo se aceptan imágenes y videos: el módulo media no es un depósito genérico. */
    private static MediaKind resolveKind(String contentType) {
        if (contentType.startsWith("image/")) {
            return MediaKind.PHOTO;
        }
        if (contentType.startsWith("video/")) {
            return MediaKind.VIDEO;
        }
        throw new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Solo se aceptan imágenes y videos");
    }
}