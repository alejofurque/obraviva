package com.obraviva.platform.captures3d;

import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reglas de una captura nueva, que comparten el alta de un SPZ y el alta desde video: la
 * etapa y la casa tienen que ser de la obra, la fecha no puede ser futura, el sector tiene
 * que ser de la casa y estar libre ese día, y la captura hereda la alineación de la última
 * de su sector. También quién puede trabajar sobre una captura y cómo se borra entera.
 *
 * <p>No abre transacciones: corre dentro de la del servicio que la llama.
 */
@Component
class CaptureRules {

    private final Capture3dRepository captureRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final ProcessingJobService jobService;
    private final StageCatalogService stageCatalog;
    private final UnitCatalogService unitCatalog;
    private final CaptureSectorService sectorService;

    CaptureRules(
            Capture3dRepository captureRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            ProcessingJobService jobService,
            StageCatalogService stageCatalog,
            UnitCatalogService unitCatalog,
            CaptureSectorService sectorService) {
        this.captureRepository = captureRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.jobService = jobService;
        this.stageCatalog = stageCatalog;
        this.unitCatalog = unitCatalog;
        this.sectorService = sectorService;
    }

    /**
     * La etapa y la unidad que manda el cliente tienen que ser de esta obra. Lo que llega
     * del navegador no es confiable: sin esto, una captura podría quedar asociada a la
     * etapa de otro desarrollo.
     */
    void requireReferencesOf(UUID organizationId, UUID developmentId, UUID unitId, UUID stageId) {
        if (stageId != null && stageCatalog.stagesOf(developmentId, List.of(organizationId)).stream()
                .noneMatch(stage -> stage.id().equals(stageId)
                        && (stage.unitId() == null || stage.unitId().equals(unitId)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La etapa no pertenece a esta obra");
        }
        if (unitId != null && unitCatalog.findInOrganization(unitId, organizationId)
                .filter(unit -> unit.developmentId().equals(developmentId))
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }
    }

    /**
     * Dónde y cuándo cae un recorrido: la fecha no puede ser futura, el sector tiene que ser
     * uno de los que el responsable definió para la casa, y ese sector no puede tener ya un
     * recorrido ese día.
     */
    void requirePlace(
            UUID organizationId, UUID developmentId, UUID unitId, String zone, LocalDate date, UUID except) {
        requirePlace(organizationId, developmentId, unitId, zone, date, except, true);
    }

    /** {@code checkSector} falso: corregir otro dato de un recorrido viejo cuyo sector ya no está en la casa. */
    void requirePlace(
            UUID organizationId, UUID developmentId, UUID unitId, String zone, LocalDate date, UUID except,
            boolean checkSector) {
        if (date.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha del recorrido no puede ser futura");
        }
        if (checkSector && unitId != null && zone != null && !sectorService.names(unitId).contains(zone)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"%s\" no es un sector de esta casa. Los sectores se definen en la pestaña Recorridos 3D de la casa."
                            .formatted(zone));
        }
        requireSectorDateFree(organizationId, developmentId, unitId, zone, date, except);
    }

    /**
     * Un recorrido por sector y día: dos le aparecerían al comprador como dos momentos
     * iguales. Bloquean el disponible y el video que se está reconstruyendo. Un video que
     * falló o que nunca se terminó de subir, y un SPZ sin confirmar, no bloquean: si no, una
     * subida abandonada trabaría el sector ese día para siempre.
     */
    void requireSectorDateFree(
            UUID organizationId, UUID developmentId, UUID unitId, String zone, LocalDate date, UUID except) {
        if (zone == null) {
            return;
        }
        List<Capture3d> same = captureRepository
                .findByDevelopmentIdAndOrganizationIdAndCaptureDate(developmentId, organizationId, date).stream()
                .filter(other -> !other.getId().equals(except))
                .filter(other -> Objects.equals(other.getUnitId(), unitId) && zone.equals(other.getZone()))
                .toList();
        if (same.isEmpty()) {
            return;
        }
        String day = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        if (same.stream().anyMatch(other -> other.getStatus() == CaptureStatus.AVAILABLE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "%s ya tiene un recorrido del %s. Elegí otra fecha.".formatted(zone, day));
        }
        Map<UUID, ProcessingJob> jobs = jobService.findByResources(
                List.of(organizationId), same.stream().map(Capture3d::getId).toList());
        boolean reconstructing = jobs.values().stream().map(ProcessingJob::getStatus)
                .anyMatch(status -> status == JobStatus.PENDING || status == JobStatus.PROCESSING
                        || status == JobStatus.NEEDS_REVIEW);
        if (reconstructing) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "%s ya tiene un recorrido del %s reconstruyéndose. Esperá a que termine o falle.".formatted(zone, day));
        }
    }

    /**
     * Una captura nueva arranca con la alineación de la última disponible de su sector, sea
     * de video o de Scaniverse: si se filmó desde el mismo lugar, el viaje en el tiempo queda
     * aproximadamente alineado sin que nadie toque nada. Sin sector o sin capturas previas,
     * queda la identidad.
     */
    void inheritAlignment(Capture3d capture) {
        if (capture.getZone() == null) {
            return;
        }
        captureRepository.findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCaptureDateAsc(
                        capture.getDevelopmentId(), List.of(capture.getOrganizationId()), CaptureStatus.AVAILABLE)
                .stream()
                .filter(previous -> Objects.equals(previous.getUnitId(), capture.getUnitId())
                        && capture.getZone().equals(previous.getZone()))
                .max(Comparator.comparing(Capture3d::getCaptureDate).thenComparing(Capture3d::getCreatedAt))
                .map(Capture3d::getTransform)
                .ifPresent(transform -> capture.updateTransform(new SpatialTransform(
                        transform.getPositionX(), transform.getPositionY(), transform.getPositionZ(),
                        transform.getRotationX(), transform.getRotationY(), transform.getRotationZ(),
                        transform.getScale())));
    }

    /**
     * Para lo que se hace sobre una captura de una casa: llegar al desarrollo no alcanza.
     * Pregunta con {@code canWorkOnUnit} y lanza acá, con las mismas respuestas que
     * {@code requireWorkOnUnit}: si lanzara adentro de aquel método transaccional, la
     * transacción quedaría marcada para revertir y las confirmaciones, que no revierten
     * ante su propio rechazo, fallarían al cerrar.
     */
    Capture3d requireWorkable(UUID userId, UUID captureId) {
        Capture3d capture = requireAccessible(userId, captureId);
        if (!developmentAccess.canWorkOnUnit(
                userId, capture.getDevelopmentId(), capture.getOrganizationId(), capture.getUnitId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, capture.getUnitId() == null
                    ? "Las obras comunes las carga un encargado de todo el desarrollo"
                    : "No estás asignado a esta casa");
        }
        return capture;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    Capture3d requireAccessible(UUID userId, UUID captureId) {
        return captureRepository
                .findByIdAndOrganizationIdIn(captureId, membershipService.organizationIdsOf(userId))
                .filter(capture -> developmentAccess.canAccess(userId, capture.getDevelopmentId(), capture.getOrganizationId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Captura no encontrada"));
    }

    /** Borra la captura, su trabajo y sus archivos. Lo que no se pueda borrar queda huérfano e invisible. */
    void remove(Capture3d capture) {
        jobService.deleteByResource(capture.getId());
        captureRepository.delete(capture);
        for (String key : new String[] {
                capture.getVideoStorageKey(), capture.getNormalizedVideoStorageKey(),
                capture.getResultStorageKey(), capture.getStorageKey()}) {
            deleteQuietly(key);
        }
    }

    /** Un archivo que no se pudo borrar queda huérfano e invisible, no rompe nada. */
    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storageService.delete(key);
        } catch (RuntimeException e) {
            // Puede no existir: una subida abandonada nunca llegó a crear el archivo.
        }
    }
}
