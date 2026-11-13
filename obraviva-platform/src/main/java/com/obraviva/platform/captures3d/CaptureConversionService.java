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
 * Fronteras transaccionales del paso de conversión al formato de distribución.
 *
 * <p>Las mutaciones de trabajos pasan siempre por {@link ProcessingJobService}: este
 * módulo no toca entidades del módulo de trabajos.
 */
@Service
public class CaptureConversionService {

    private static final Logger log = LoggerFactory.getLogger(CaptureConversionService.class);

    private static final Set<PipelineStep> SUPPORTED_STEPS = Set.of(PipelineStep.CONVERT);

    private final Capture3dRepository captureRepository;
    private final ProcessingJobService jobService;
    private final int maxAttempts;

    public CaptureConversionService(
            Capture3dRepository captureRepository,
            ProcessingJobService jobService,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.captureRepository = captureRepository;
        this.jobService = jobService;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Toma el trabajo pendiente más antiguo posicionado en el paso de conversión.
     *
     * <p>La búsqueda de la captura no se acota por organización porque no hay usuario
     * detrás: es un proceso de fondo. La garantía equivalente es verificar que la
     * captura pertenezca a la misma organización que el trabajo.
     */
    @Transactional
    public Optional<CaptureConversionTask> claimNext() {
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

        if (capture.getResultStorageKey() == null) {
            // No debería ocurrir: el paso previo asigna la clave antes de avanzar.
            jobService.fail(job.getId(),
                    "La captura no tiene un resultado de reconstrucción para convertir");
            return Optional.empty();
        }

        return Optional.of(new CaptureConversionTask(
                job.getId(),
                capture.getId(),
                capture.getResultStorageKey(),
                capture.getStorageKey()));
    }

    /**
     * El SPZ quedó almacenado: la captura pasa a estar disponible y el trabajo se
     * cierra.
     *
     * <p>Las dos cosas ocurren en la misma transacción a propósito. Una captura
     * disponible con el trabajo abierto sería reprocesada; un trabajo cerrado con la
     * captura no disponible dejaría un recorrido invisible en el visor pese a estar
     * el archivo en su lugar.
     */
    @Transactional
    public void recordSuccess(UUID jobId, UUID captureId) {
        captureRepository.findById(captureId).ifPresent(Capture3d::markAvailable);
        jobService.finishPipeline(jobId);
        log.info("Captura {} publicada y disponible en el visor", captureId);
    }

    /** El resultado no sirve: reintentar con el mismo archivo daría lo mismo. */
    @Transactional
    public void recordInvalidResult(UUID jobId, String message) {
        jobService.fail(jobId, message);
    }

    /** Falla técnica (herramienta ausente, almacenamiento caído, timeout). */
    @Transactional
    public void recordTechnicalFailure(UUID jobId, String error) {
        jobService.retryOrFail(jobId, error, maxAttempts);
    }
}