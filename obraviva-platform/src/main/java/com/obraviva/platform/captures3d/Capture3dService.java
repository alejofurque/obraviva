package com.obraviva.platform.captures3d;

import com.obraviva.platform.captures3d.CaptureDtos.CaptureProcessingResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureUploadResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateTransformRequest;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.obraviva.platform.captures3d.CaptureRules.blankToNull;

/**
 * Recorridos 3D: el alta de un SPZ ya procesado, la consulta, la corrección, el descarte y la
 * alineación. El alta desde video está en {@link VideoCaptureService}; las reglas que comparten
 * las dos altas, en {@link CaptureRules}.
 */
@Service
public class Capture3dService {

    /** SPZ no tiene tipo MIME registrado; se sube como binario genérico. */
    private static final String SPZ_CONTENT_TYPE = "application/octet-stream";

    private final Capture3dRepository captureRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final ProcessingJobService jobService;
    private final CaptureRules rules;

    public Capture3dService(
            Capture3dRepository captureRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            ProcessingJobService jobService,
            CaptureRules rules) {
        this.captureRepository = captureRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.jobService = jobService;
        this.rules = rules;
    }

    // --- Alta directa de SPZ ya procesado ---

    @Transactional
    public CaptureUploadResponse createCapture(
            UUID userId, UUID developmentId, CreateCaptureRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        rules.requireReferencesOf(organizationId, developmentId, request.unitId(), request.stageId());
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
        SpzValidation.problemOfDeclared(request.filename(), request.fileSize()).ifPresent(problem -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        });
        String zone = blankToNull(request.zone());
        rules.requirePlace(organizationId, developmentId, request.unitId(), zone, request.captureDate(), null);

        Capture3d capture = new Capture3d(
                organizationId,
                developmentId,
                request.unitId(),
                request.stageId(),
                request.captureDate(),
                request.description(),
                userId);
        capture.assignZone(zone);
        rules.inheritAlignment(capture);
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
            UUID userId, UUID developmentId, CreateCaptureRequest request, Path file) {
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
        Capture3d capture = rules.requireWorkable(userId, captureId);

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
            rules.remove(capture);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem.get());
        }
        try {
            rules.requireSectorDateFree(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
                    capture.getZone(), capture.getCaptureDate(), capture.getId());
        } catch (ResponseStatusException conflict) {
            rules.remove(capture);
            throw conflict;
        }

        capture.markAvailable();
        return toResponse(capture);
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
        Capture3d capture = rules.requireWorkable(userId, captureId);
        rules.requireReferencesOf(capture.getOrganizationId(), capture.getDevelopmentId(), null, request.stageId());
        // Corregir la fecha o el sector no puede dejar dos recorridos del mismo sector el mismo día.
        String zone = blankToNull(request.zone());
        rules.requirePlace(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
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
        Capture3d capture = rules.requireWorkable(userId, captureId);
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

        rules.remove(capture);
    }

    /** Alineación manual desde el panel de administración, reservada al dueño. */
    @Transactional
    public CaptureResponse updateTransform(
            UUID userId, UUID captureId, UpdateTransformRequest request) {
        Capture3d capture = rules.requireAccessible(userId, captureId);
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
}