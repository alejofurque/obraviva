package com.obraviva.platform.captures3d;

import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fronteras transaccionales del preprocesamiento de video. El worker orquesta pero no
 * abre transacciones: las operaciones de base son cortas y viven acá.
 *
 * <p>Las mutaciones de trabajos pasan siempre por {@link ProcessingJobService}: este
 * módulo no toca entidades del módulo de trabajos.
 */
@Service
public class VideoPreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoPreprocessingService.class);

    /**
     * Pasos que este servicio sabe ejecutar. Los demás quedan sin reclamar hasta que
     * exista quien los atienda (tarea 2.6c-3b).
     */
    private static final Set<PipelineStep> SUPPORTED_STEPS = Set.of(PipelineStep.PREPROCESS);

    private final Capture3dRepository captureRepository;
    private final ProcessingJobService jobService;
    private final int maxAttempts;

    public VideoPreprocessingService(
            Capture3dRepository captureRepository,
            ProcessingJobService jobService,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.captureRepository = captureRepository;
        this.jobService = jobService;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Toma el trabajo pendiente más antiguo que esté en un paso soportado y arma la
     * tarea. Devuelve vacío cuando no hay nada que hacer o cuando el trabajo se
     * descartó por datos inconsistentes.
     *
     * <p>La búsqueda de la captura no se acota por organización porque no hay usuario
     * detrás: es un proceso de fondo. La garantía equivalente es verificar que la
     * captura pertenezca a la misma organización que el trabajo.
     */
    @Transactional
    public Optional<VideoPreprocessingTask> claimNext() {
        Optional<ProcessingJob> claimed =
                jobService.claimNext(JobType.CAPTURE_3D_RECONSTRUCTION, SUPPORTED_STEPS);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        ProcessingJob job = claimed.get();
        Capture3d capture = captureRepository.findById(job.getResourceId()).orElse(null);

        if (capture == null || !capture.getOrganizationId().equals(job.getOrganizationId())) {
            log.warn("Trabajo {} descartado: la captura {} no existe o pertenece a otra organización",
                    job.getId(), job.getResourceId());
            jobService.fail(job.getId(),
                    "La captura asociada al trabajo no existe o no pertenece a la organización");
            return Optional.empty();
        }

        if (capture.getVideoStorageKey() == null) {
            jobService.fail(job.getId(), "La captura no tiene un video de origen asociado");
            return Optional.empty();
        }

        capture.markVideoNormalized();

        return Optional.of(new VideoPreprocessingTask(
                job.getId(),
                capture.getId(),
                capture.getVideoStorageKey(),
                capture.getNormalizedVideoStorageKey()));
    }

    /** El video quedó normalizado y almacenado: el trabajo avanza al paso de envío. */
    @Transactional
    public void recordSuccess(UUID jobId) {
        jobService.completeStep(jobId, PipelineStep.SUBMIT);
    }

    /** El video no sirve. Reintentar con el mismo archivo daría el mismo resultado. */
    @Transactional
    public void recordValidationFailure(UUID jobId, String userMessage) {
        jobService.fail(jobId, userMessage);
    }

    /** Falla del sistema (herramienta ausente, almacenamiento caído, timeout). */
    @Transactional
    public void recordTechnicalFailure(UUID jobId, String error) {
        jobService.retryOrFail(jobId, error, maxAttempts);
    }
}