package com.obraviva.platform.captures3d;

import com.obraviva.platform.captures3d.CaptureDtos.CaptureJobResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureProcessingResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureUploadResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateTransformRequest;
import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class Capture3dService {

    /** SPZ no tiene tipo MIME registrado; se sube como binario genérico. */
    private static final String SPZ_CONTENT_TYPE = "application/octet-stream";

    private final Capture3dRepository captureRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final ProcessingJobService jobService;
    private final StageCatalogService stageCatalog;
    private final UnitCatalogService unitCatalog;
    private final CaptureSectorService sectorService;

    public Capture3dService(
            Capture3dRepository captureRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            ProcessingJobService jobService,
            StageCatalogService stageCatalog,
            UnitCatalogService unitCatalog,
            CaptureSectorService sectorService) {
        this.sectorService = sectorService;
        this.captureRepository = captureRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.jobService = jobService;
        this.stageCatalog = stageCatalog;
        this.unitCatalog = unitCatalog;
    }

    // --- Alta directa de SPZ ya procesado ---

    @Transactional
    public CaptureUploadResponse createCapture(
            UUID userId, UUID developmentId, CreateCaptureRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        requireReferencesOf(organizationId, developmentId, request.unitId(), request.stageId());
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
        SpzValidation.problemOfDeclared(request.filename(), request.fileSize()).ifPresent(problem -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        });
        String zone = blankToNull(request.zone());
        requirePlace(organizationId, developmentId, request.unitId(), zone, request.captureDate(), null);

        Capture3d capture = new Capture3d(
                organizationId,
                developmentId,
                request.unitId(),
                request.stageId(),
                request.captureDate(),
                request.description(),
                userId);
        capture.assignZone(zone);
        inheritAlignment(capture);
        captureRepository.save(capture);

        String uploadUrl = storageService.presignUpload(capture.getStorageKey(), SPZ_CONTENT_TYPE);
        return new CaptureUploadResponse(capture.getId(), uploadUrl, SPZ_CONTENT_TYPE);
    }

    /**
     * Publica una captura SPZ que ya está en el servidor. Lo usan los datos de demostración;
     * valida etapa y unidad igual que el alta normal.
     */
    @Transactional
    public CaptureResponse importSplat(
            UUID userId, UUID developmentId, CreateCaptureRequest request, java.nio.file.Path file) {
        CaptureUploadResponse upload = createCapture(userId, developmentId, request);
        Capture3d capture = captureRepository.findById(upload.captureId()).orElseThrow();
        storageService.upload(file, capture.getStorageKey(), SPZ_CONTENT_TYPE);
        capture.markAvailable();
        return toResponse(capture);
    }

    /**
     * Publica el SPZ subido después de medirlo: tamaño real y encabezado. Si no sirve, o si
     * mientras tanto otro recorrido ocupó el sector ese día, se borran el archivo y la
     * captura y no queda nada: por eso este método no revierte ante el error que devuelve.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public CaptureResponse confirmUpload(UUID userId, UUID captureId) {
        Capture3d capture = requireWorkable(userId, captureId);

        if (!storageService.exists(capture.getStorageKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El archivo de la captura no se encuentra en el almacenamiento");
        }
        if (capture.getStatus() == CaptureStatus.AVAILABLE) {
            return toResponse(capture);
        }

        String key = capture.getStorageKey();
        Optional<String> problem = SpzValidation.problemOfSize(storageService.size(key))
                .or(() -> SpzValidation.problemOfPrefix(storageService.readPrefix(key, SpzValidation.PREFIX_BYTES)));
        if (problem.isPresent()) {
            remove(capture);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem.get());
        }
        try {
            requireSectorDateFree(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
                    capture.getZone(), capture.getCaptureDate(), capture.getId());
        } catch (ResponseStatusException conflict) {
            remove(capture);
            throw conflict;
        }

        capture.markAvailable();
        return toResponse(capture);
    }

    // --- Alta desde video (pipeline de reconstrucción) ---

    /** Crea la captura, registra el trabajo asincrónico y devuelve la URL de subida del video. */
    @Transactional
    public CaptureJobResponse createFromVideo(
            UUID userId, UUID developmentId, CreateCaptureFromVideoRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);

        if (!request.videoContentType().startsWith("video/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El archivo debe ser un video");
        }
        requireReferencesOf(organizationId, developmentId, request.unitId(), request.stageId());
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
        String zone = blankToNull(request.zone());
        requirePlace(organizationId, developmentId, request.unitId(), zone, request.captureDate(), null);

        Capture3d capture = new Capture3d(
                organizationId,
                developmentId,
                request.unitId(),
                request.stageId(),
                request.captureDate(),
                request.description(),
                userId);
        capture.assignZone(zone);
        inheritAlignment(capture);
        capture.attachSourceVideo(extensionOf(request.videoFilename()));
        captureRepository.save(capture);

        ProcessingJob job = jobService.create(
                organizationId, JobType.CAPTURE_3D_RECONSTRUCTION, capture.getId());

        String uploadUrl = storageService.presignUpload(
                capture.getVideoStorageKey(), request.videoContentType());

        return new CaptureJobResponse(
                capture.getId(),
                job.getId(),
                job.getStatus().name(),
                uploadUrl,
                request.videoContentType());
    }

    /**
     * Confirma la subida del video verificando el almacenamiento; encola el trabajo. Si
     * mientras subía otro recorrido ocupó el sector ese día, se descarta sin encolar nada:
     * reconstruirlo gastaría un crédito en un recorrido que no se puede publicar.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public CaptureJobResponse confirmVideoUpload(UUID userId, UUID captureId) {
        Capture3d capture = requireWorkable(userId, captureId);

        if (capture.getVideoStorageKey() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La captura no tiene un video asociado");
        }
        if (!storageService.exists(capture.getVideoStorageKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El video no se encuentra en el almacenamiento");
        }
        try {
            requireSectorDateFree(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
                    capture.getZone(), capture.getCaptureDate(), capture.getId());
        } catch (ResponseStatusException conflict) {
            remove(capture);
            throw conflict;
        }

        jobService.markReady(captureId);
        ProcessingJob job = jobService.findByResource(captureId).orElseThrow();

        return new CaptureJobResponse(
                captureId, job.getId(), job.getStatus().name(), null, null);
    }

    // --- Consulta y alineación ---

    @Transactional(readOnly = true)
    public List<CaptureResponse> listAvailable(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        Predicate<UUID> readable = developmentAccess.readableUnits(userId, developmentId, organizationId);
        return captureRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCaptureDateAsc(
                        developmentId,
                        membershipService.organizationIdsOf(userId),
                        CaptureStatus.AVAILABLE)
                .stream()
                .filter(capture -> readable.test(capture.getUnitId()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Capturas originadas en video que todavía no están disponibles, con el estado de
     * su trabajo. Es lo que consulta la pantalla de subida.
     *
     * <p>Una captura pendiente sin trabajo asociado viene del alta manual de un SPZ:
     * no atraviesa el pipeline y no tiene nada que seguir, así que no se lista.
     */
    @Transactional(readOnly = true)
    public List<CaptureProcessingResponse> listProcessing(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        Predicate<UUID> readable = developmentAccess.readableUnits(userId, developmentId, organizationId);
        List<UUID> organizationIds = membershipService.organizationIdsOf(userId);

        List<Capture3d> pending = captureRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCreatedAtDesc(
                        developmentId, organizationIds, CaptureStatus.PENDING)
                .stream()
                .filter(capture -> readable.test(capture.getUnitId()))
                .toList();
        Map<UUID, ProcessingJob> jobs = jobService.findByResources(
                organizationIds, pending.stream().map(Capture3d::getId).toList());

        return pending.stream()
                .filter(capture -> jobs.containsKey(capture.getId()))
                .map(capture -> toProcessingResponse(capture, jobs.get(capture.getId()), userId))
                .toList();
    }

    /**
     * Corrige fecha, descripción, ambiente y etapa. Lo hace quien puede subir capturas:
     * el dueño y el encargado. La alineación espacial queda aparte, reservada al dueño.
     */
    @Transactional
    public CaptureResponse updateCapture(UUID userId, UUID captureId, UpdateCaptureRequest request) {
        Capture3d capture = requireWorkable(userId, captureId);
        requireReferencesOf(capture.getOrganizationId(), capture.getDevelopmentId(), null, request.stageId());
        // Corregir la fecha o el sector no puede dejar dos recorridos del mismo sector el mismo día.
        String zone = blankToNull(request.zone());
        requirePlace(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
                zone, request.captureDate(), capture.getId(), !Objects.equals(zone, capture.getZone()));
        capture.updateDetails(
                request.captureDate(),
                blankToNull(request.description()),
                blankToNull(request.zone()),
                request.stageId());
        return toResponse(capture);
    }

    /**
     * Descarta un recorrido que no llegó a estar disponible: una subida abandonada o un
     * procesamiento que falló. Sin esto quedan para siempre en la lista de pendientes.
     *
     * <p>El dueño descarta cualquiera; el encargado, sólo los que subió él. Un recorrido
     * disponible no se descarta desde acá, porque ya forma parte del viaje en el tiempo, y
     * uno que se está procesando tampoco, porque un worker lo tiene tomado.
     */
    @Transactional
    public void discard(UUID userId, UUID captureId) {
        Capture3d capture = requireWorkable(userId, captureId);
        if (capture.getStatus() == CaptureStatus.AVAILABLE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El recorrido ya está disponible y forma parte de la historia de la obra");
        }

        ProcessingJob job = jobService.findByResource(captureId).orElse(null);
        if (job != null && job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.UPLOADED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El recorrido se está procesando. Esperá a que termine o falle para descartarlo");
        }

        boolean owner = membershipService.isOwnerOf(userId, capture.getOrganizationId());
        if (!owner && !capture.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Sólo el dueño de la obra puede descartar lo que subió otra persona");
        }

        remove(capture);
    }

    /** Borra la captura, su trabajo y sus archivos. Lo que no se pueda borrar queda huérfano e invisible. */
    private void remove(Capture3d capture) {
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

    /** Alineación manual desde el panel de administración, reservada al dueño. */
    @Transactional
    public CaptureResponse updateTransform(
            UUID userId, UUID captureId, UpdateTransformRequest request) {
        Capture3d capture = requireAccessible(userId, captureId);
        developmentAccess.requireOwnerOf(userId, capture.getOrganizationId());
        capture.updateTransform(request.transform().toEntity());
        return toResponse(capture);
    }

    // --- Común ---

    private CaptureResponse toResponse(Capture3d capture) {
        String splatUrl = capture.getStatus() == CaptureStatus.AVAILABLE
                ? storageService.presignDownload(capture.getStorageKey())
                : null;
        return CaptureResponse.from(capture, splatUrl);
    }

    private static CaptureProcessingResponse toProcessingResponse(
            Capture3d capture, ProcessingJob job, UUID viewerId) {
        String failureReason = job.getStatus() == JobStatus.FAILED ? job.getLastError() : null;
        return new CaptureProcessingResponse(
                capture.getId(),
                capture.getUnitId(),
                capture.getStageId(),
                capture.getCaptureDate(),
                capture.getDescription(),
                capture.getZone(),
                capture.getCreatedAt(),
                job.getStatus().name(),
                job.getPipelineStep().name(),
                job.getStepStartedAt(),
                failureReason,
                capture.getCreatedBy().equals(viewerId));
    }

    /**
     * La etapa y la unidad que manda el cliente tienen que ser de esta obra. Lo que llega
     * del navegador no es confiable: sin esto, una captura podría quedar asociada a la
     * etapa de otro desarrollo.
     */
    private void requireReferencesOf(UUID organizationId, UUID developmentId, UUID unitId, UUID stageId) {
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
    private void requirePlace(
            UUID organizationId, UUID developmentId, UUID unitId, String zone, LocalDate date, UUID except) {
        requirePlace(organizationId, developmentId, unitId, zone, date, except, true);
    }

    /** {@code checkSector} falso: corregir otro dato de un recorrido viejo cuyo sector ya no está en la casa. */
    private void requirePlace(
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
    private void requireSectorDateFree(
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
    private void inheritAlignment(Capture3d capture) {
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
    private Capture3d requireWorkable(UUID userId, UUID captureId) {
        Capture3d capture = requireAccessible(userId, captureId);
        if (!developmentAccess.canWorkOnUnit(
                userId, capture.getDevelopmentId(), capture.getOrganizationId(), capture.getUnitId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, capture.getUnitId() == null
                    ? "Las obras comunes las carga un encargado de todo el desarrollo"
                    : "No estás asignado a esta casa");
        }
        return capture;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Capture3d requireAccessible(UUID userId, UUID captureId) {
        return captureRepository
                .findByIdAndOrganizationIdIn(captureId, membershipService.organizationIdsOf(userId))
                .filter(capture -> developmentAccess.canAccess(userId, capture.getDevelopmentId(), capture.getOrganizationId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Captura no encontrada"));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase()
                : "mp4";
    }
}