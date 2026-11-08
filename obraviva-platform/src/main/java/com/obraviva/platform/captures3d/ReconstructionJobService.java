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
 * Fronteras transaccionales de los pasos que dialogan con el servicio externo de
 * reconstrucción: envío, espera y descarga.
 *
 * <p>Las mutaciones de trabajos pasan siempre por {@link ProcessingJobService}: este
 * módulo no toca entidades del módulo de trabajos.
 */
@Service
public class ReconstructionJobService {

    private static final Logger log = LoggerFactory.getLogger(ReconstructionJobService.class);

    private static final Set<PipelineStep> SUPPORTED_STEPS = Set.of(
            PipelineStep.SUBMIT,
            PipelineStep.AWAIT_EXTERNAL,
            PipelineStep.DOWNLOAD);

    private final Capture3dRepository captureRepository;
    private final ProcessingJobService jobService;
    private final int maxAttempts;

    public ReconstructionJobService(
            Capture3dRepository captureRepository,
            ProcessingJobService jobService,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.captureRepository = captureRepository;
        this.jobService = jobService;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Toma el trabajo pendiente más antiguo de alguno de los pasos soportados y arma
     * la tarea correspondiente.
     *
     * <p>La búsqueda de la captura no se acota por organización porque no hay usuario
     * detrás: es un proceso de fondo. La garantía equivalente es verificar que la
     * captura pertenezca a la misma organización que el trabajo.
     */
    @Transactional
    public Optional<ClaimedStep> claimNext() {
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

        return switch (job.getPipelineStep()) {
            case SUBMIT -> claimSubmission(job, capture);
            case AWAIT_EXTERNAL -> claimPoll(job, capture);
            case DOWNLOAD -> claimDownload(job, capture);
            default -> {
                // No debería ocurrir: el reclamo filtra por pasos soportados.
                jobService.fail(job.getId(),
                        "El trabajo está en un paso que este worker no sabe ejecutar: "
                                + job.getPipelineStep());
                yield Optional.empty();
            }
        };
    }

    private Optional<ClaimedStep> claimSubmission(ProcessingJob job, Capture3d capture) {
        if (capture.getNormalizedVideoStorageKey() == null) {
            jobService.fail(job.getId(),
                    "La captura no tiene un video normalizado para enviar a reconstrucción");
            return Optional.empty();
        }
        return Optional.of(new ClaimedStep(PipelineStep.SUBMIT, new ReconstructionSubmissionTask(
                job.getId(), capture.getId(), capture.getNormalizedVideoStorageKey())));
    }

    private Optional<ClaimedStep> claimPoll(ProcessingJob job, Capture3d capture) {
        if (job.getExternalReference() == null) {
            jobService.fail(job.getId(),
                    "El trabajo espera un resultado externo pero no tiene referencia del servicio");
            return Optional.empty();
        }
        return Optional.of(new ClaimedStep(PipelineStep.AWAIT_EXTERNAL, new ReconstructionPollTask(
                job.getId(), capture.getId(), job.getExternalReference(), job.timeInCurrentStep())));
    }

    private Optional<ClaimedStep> claimDownload(ProcessingJob job, Capture3d capture) {
        if (job.getExternalReference() == null) {
            jobService.fail(job.getId(),
                    "El trabajo debe descargar un resultado pero no tiene referencia del servicio");
            return Optional.empty();
        }
        capture.markResultKey();
        return Optional.of(new ClaimedStep(PipelineStep.DOWNLOAD, new ReconstructionDownloadTask(
                job.getId(), capture.getId(), job.getExternalReference(),
                capture.getResultStorageKey())));
    }

    // --- Desenlaces ---

    /**
     * El servicio aceptó el video: se registra su identificador y el trabajo pasa a
     * esperar el resultado. Este es el momento en que se consumió un crédito.
     */
    @Transactional
    public void recordSubmission(UUID jobId, String externalReference) {
        jobService.completeStep(jobId, PipelineStep.AWAIT_EXTERNAL, externalReference);
    }

    /** El servicio sigue trabajando: se reencola sin avanzar y sin contar como fallo. */
    @Transactional
    public void recordStillProcessing(UUID jobId) {
        jobService.awaitAgain(jobId);
    }

    /** El servicio terminó: el trabajo avanza a la descarga del resultado. */
    @Transactional
    public void recordReadyForDownload(UUID jobId) {
        jobService.completeStep(jobId, PipelineStep.DOWNLOAD);
    }

    /** El resultado quedó guardado en el almacenamiento propio: falta convertirlo. */
    @Transactional
    public void recordDownloaded(UUID jobId) {
        jobService.completeStep(jobId, PipelineStep.CONVERT);
    }

    /** Rechazo definitivo del servicio: reintentar no cambia el resultado. */
    @Transactional
    public void recordRejection(UUID jobId, String userMessage) {
        jobService.fail(jobId, userMessage);
    }

    /** Falla técnica (red, timeout, error del proveedor): el trabajo vuelve a la cola. */
    @Transactional
    public void recordTechnicalFailure(UUID jobId, String error) {
        jobService.retryOrFail(jobId, error, maxAttempts);
    }
}