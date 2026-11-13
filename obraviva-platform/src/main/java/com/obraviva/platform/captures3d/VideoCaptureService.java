package com.obraviva.platform.captures3d;

import com.obraviva.platform.captures3d.CaptureDtos.CaptureJobResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static com.obraviva.platform.captures3d.CaptureRules.blankToNull;

/**
 * Alta de un recorrido desde video: crea la captura y su trabajo, entrega la URL para subir el
 * video y, al confirmarlo, lo deja en la cola de reconstrucción. Lo que sigue lo hacen los
 * workers del pipeline.
 */
@Service
public class VideoCaptureService {

    private final Capture3dRepository captureRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final ProcessingJobService jobService;
    private final CaptureRules rules;

    public VideoCaptureService(
            Capture3dRepository captureRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            ProcessingJobService jobService,
            CaptureRules rules) {
        this.captureRepository = captureRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.jobService = jobService;
        this.rules = rules;
    }

    /** Crea la captura, registra el trabajo asincrónico y devuelve la URL de subida del video. */
    @Transactional
    public CaptureJobResponse createFromVideo(
            UUID userId, UUID developmentId, CreateCaptureFromVideoRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);

        if (!request.videoContentType().startsWith("video/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El archivo debe ser un video");
        }
        rules.requireReferencesOf(organizationId, developmentId, request.unitId(), request.stageId());
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
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
        Capture3d capture = rules.requireWorkable(userId, captureId);

        if (capture.getVideoStorageKey() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La captura no tiene un video asociado");
        }
        if (!storageService.exists(capture.getVideoStorageKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El video no se encuentra en el almacenamiento");
        }
        try {
            rules.requireSectorDateFree(capture.getOrganizationId(), capture.getDevelopmentId(), capture.getUnitId(),
                    capture.getZone(), capture.getCaptureDate(), capture.getId());
        } catch (ResponseStatusException conflict) {
            rules.remove(capture);
            throw conflict;
        }

        jobService.markReady(captureId);
        ProcessingJob job = jobService.findByResource(captureId).orElseThrow();

        return new CaptureJobResponse(
                captureId, job.getId(), job.getStatus().name(), null, null);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase()
                : "mp4";
    }
}
