package com.obraviva.platform.publication;

import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.StageStatus;
import com.obraviva.platform.media.MediaCatalogService;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest.StageUpdate;
import com.obraviva.platform.progress.ProgressEntryResponse;
import com.obraviva.platform.progress.ProgressService;
import com.obraviva.platform.progress.ProgressSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publicar un avance: el texto, sus fotos y, si se pide, el avance y el estado de su
 * etapa, en una sola transacción. El comprador ve todo junto o no ve nada.
 *
 * <p>Vive aparte porque junta tres módulos: media ya depende de progress para ubicar las
 * fotos de un avance, y que progress dependiera de media cerraría el círculo. Cada módulo
 * se usa por su API pública.
 *
 * <p>El orden es el que deja a prueba la atomicidad: primero el avance (que valida el
 * acceso, la casa, la etapa y la fecha), después la etapa y al final las fotos, que son lo
 * único que se verifica contra el almacenamiento. Una foto que falla revierte lo anterior.
 */
@Service
public class ProgressPublicationService {

    private final ProgressService progressService;
    private final StageCatalogService stageCatalog;
    private final MediaCatalogService mediaCatalog;
    private final DevelopmentAccessService developmentAccess;
    private final CaptureSectorService sectorService;

    public ProgressPublicationService(
            ProgressService progressService, StageCatalogService stageCatalog,
            MediaCatalogService mediaCatalog, DevelopmentAccessService developmentAccess,
            CaptureSectorService sectorService) {
        this.sectorService = sectorService;
        this.progressService = progressService;
        this.stageCatalog = stageCatalog;
        this.mediaCatalog = mediaCatalog;
        this.developmentAccess = developmentAccess;
    }

    @Transactional
    public ProgressEntryResponse publish(UUID userId, UUID developmentId, CreateProgressEntryRequest request) {
        return publishAll(userId, developmentId, List.of(request), ProgressSource.MANUAL).getFirst();
    }

    /**
     * Varios avances en una sola transacción: lo usa la aprobación de un audio, donde
     * cada novedad revisada es un avance. Cada uno pasa por exactamente lo mismo que
     * "Publicar avance"; si uno falla, no queda publicado ninguno.
     */
    @Transactional
    public List<ProgressEntryResponse> publishAll(
            UUID userId, UUID developmentId, List<CreateProgressEntryRequest> requests, ProgressSource source) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        return requests.stream()
                .map(request -> publishOne(userId, developmentId, organizationId, request, source))
                .toList();
    }

    /**
     * El sector de cada foto, validado contra la casa del avance. Un sector para una foto que
     * no está en el avance es un error del cliente, no algo que se ignore.
     */
    private Map<UUID, String> sectorsOf(CreateProgressEntryRequest request) {
        Map<UUID, String> sectors = new HashMap<>();
        if (request.photoSectors() == null) {
            return sectors;
        }
        for (Map.Entry<UUID, String> entry : request.photoSectors().entrySet()) {
            if (!request.photoIds().contains(entry.getKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El sector es de una foto que no está en el avance");
            }
            sectors.put(entry.getKey(), sectorService.requireSectorOf(request.unitId(), entry.getValue()));
        }
        return sectors;
    }

    private ProgressEntryResponse publishOne(
            UUID userId, UUID developmentId, UUID organizationId, CreateProgressEntryRequest request,
            ProgressSource source) {
        StageUpdate update = request.stageUpdate();
        if (update != null) {
            if (request.stageId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Para actualizar la etapa, el avance tiene que ser de una etapa");
            }
            if (update.status() == StageStatus.PENDING) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Un avance no vuelve una etapa a pendiente");
            }
        }

        ProgressEntryResponse entry = progressService.create(userId, developmentId, request, source);
        if (update != null) {
            progressService.recordStageChange(entry.id(), organizationId, stageCatalog.applyProgressUpdate(
                    request.stageId(), organizationId, update.progressPercent(), update.status(), request.entryDate()));
        }
        if (request.photoIds() != null && !request.photoIds().isEmpty()) {
            mediaCatalog.attachPendingPhotos(
                    organizationId, developmentId, request.unitId(), userId, entry.id(), request.photoIds(),
                    sectorsOf(request));
        }
        return entry;
    }
}
