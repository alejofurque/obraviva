package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.AiExtraction;
import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fronteras transaccionales del worker de audio: tomar el trabajo, guardar lo que salió de
 * cada paso y decidir qué pasa cuando falla. Cada método es una transacción corta; el trabajo
 * pesado (transcribir, interpretar) lo hace {@link AudioExtractionWorker} fuera de ellas.
 */
@Service
public class AudioExtractionJobService {

    private static final Logger log = LoggerFactory.getLogger(AudioExtractionJobService.class);

    /** Pasos que el worker de audio sabe ejecutar. */
    private static final Set<PipelineStep> SUPPORTED_STEPS =
            Set.of(PipelineStep.TRANSCRIBE, PipelineStep.INTERPRET);

    private static final String INTERRUPTED_MESSAGE = "El proceso se interrumpió a mitad del paso";

    private static final String RETRIES_EXHAUSTED_MESSAGE =
            "No se pudo procesar el audio después de varios intentos. "
                    + "Podés volver a subirlo o cargar el parte a mano.";

    private final AiExtractionRepository extractionRepository;
    private final StageCatalogService stageCatalog;
    private final ProcessingJobService jobService;
    private final int maxAttempts;

    public AudioExtractionJobService(
            AiExtractionRepository extractionRepository,
            StageCatalogService stageCatalog,
            ProcessingJobService jobService,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.extractionRepository = extractionRepository;
        this.stageCatalog = stageCatalog;
        this.jobService = jobService;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public Optional<AudioExtractionTask> claimNext() {
        Optional<ProcessingJob> claimed =
                jobService.claimNext(JobType.AUDIO_EXTRACTION, SUPPORTED_STEPS);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        ProcessingJob job = claimed.get();
        AiExtraction extraction = extractionRepository.findById(job.getResourceId()).orElse(null);

        if (extraction == null || !extraction.getOrganizationId().equals(job.getOrganizationId())) {
            log.warn("Trabajo {} descartado: la interpretación {} no existe o pertenece a otra organización",
                    job.getId(), job.getResourceId());
            jobService.fail(job.getId(),
                    "La interpretación asociada al trabajo no existe o no pertenece a la organización");
            return Optional.empty();
        }

        return Optional.of(new AudioExtractionTask(
                job.getId(),
                extraction.getId(),
                job.getPipelineStep(),
                extraction.getAudioStorageKey(),
                extraction.getTranscript(),
                stagesOf(extraction, List.of(extraction.getOrganizationId()))));
    }

    @Transactional
    public void recordTranscript(UUID jobId, UUID extractionId, String transcript) {
        extractionRepository.findById(extractionId)
                .ifPresent(extraction -> extraction.attachTranscript(transcript));
        jobService.completeStep(jobId, PipelineStep.INTERPRET);
    }

    @Transactional
    public void recordProposal(
            UUID jobId, UUID extractionId,
            String transcript, String proposalJson, String rawOutput,
            String promptVersion, String modelId) {
        extractionRepository.findById(extractionId).ifPresent(extraction ->
                extraction.markReadyForReview(
                        transcript, proposalJson, rawOutput, promptVersion, modelId));
        jobService.awaitHumanReview(jobId);
        log.info("Interpretación {} lista para revisión humana", extractionId);
    }

    @Transactional
    public void recordInvalidInput(UUID jobId, UUID extractionId, String userMessage) {
        extractionRepository.findById(extractionId)
                .ifPresent(extraction -> extraction.markFailed(userMessage));
        jobService.fail(jobId, userMessage);
    }

    /**
     * Fallo técnico: el trabajo vuelve a la cola. Si se agotaron los intentos, la
     * extracción queda marcada como fallida y no como pendiente para siempre.
     */
    @Transactional
    public void recordTechnicalFailure(UUID jobId, UUID extractionId, String error) {
        jobService.retryOrFail(jobId, error, maxAttempts);
        failExtractionIfJobDied(jobId, extractionId);
    }

    /**
     * El análisis falló pero hay respuesta del modelo: se guarda para diagnóstico
     * antes de reencolar el trabajo.
     */
    @Transactional
    public void recordFailedOutput(UUID jobId, UUID extractionId, String rawOutput, String error) {
        extractionRepository.findById(extractionId)
                .ifPresent(extraction -> extraction.attachRawOutput(rawOutput));
        jobService.retryOrFail(jobId, error, maxAttempts);
        failExtractionIfJobDied(jobId, extractionId);
    }

    /**
     * Mismo criterio que {@code ReportService}, con una diferencia deliberada: el
     * motivo que queda en la extracción es un mensaje para el revisor, que lo ve en
     * pantalla, y no el error técnico. Ese detalle ya queda en el trabajo.
     *
     * <p>La guarda de estado terminal evita que una transición inválida lance dentro
     * de esta transacción y revierta también el fallo del trabajo.
     */
    private void failExtractionIfJobDied(UUID jobId, UUID extractionId) {
        Optional<ProcessingJob> job = jobService.findByResource(extractionId);
        if (job.isEmpty() || job.get().getStatus() != JobStatus.FAILED) {
            return;
        }
        extractionRepository.findById(extractionId)
                .filter(extraction -> !extraction.getReviewStatus().isTerminal())
                .ifPresent(extraction -> extraction.markFailed(RETRIES_EXHAUSTED_MESSAGE));
        log.error("La interpretación {} quedó fallida: se agotaron los intentos del trabajo {}",
                extractionId, jobId);
    }

    /**
     * Al arrancar, un trabajo de audio en PROCESSING quedó así porque el proceso se cortó a
     * mitad de un paso: con una sola instancia y un solo hilo, nadie lo está procesando. Vuelve
     * a la cola; el intento cortado ya se contó al tomarlo, así que si era el último, la
     * extracción pasa a FAILED como cualquier fallo que agota los intentos.
     */
    @Transactional
    public int recoverInterruptedJobs() {
        List<ProcessingJob> interrupted = jobService.findInProcess(JobType.AUDIO_EXTRACTION);
        for (ProcessingJob job : interrupted) {
            jobService.retryOrFail(job.getId(), INTERRUPTED_MESSAGE, maxAttempts);
            failExtractionIfJobDied(job.getId(), job.getResourceId());
        }
        if (!interrupted.isEmpty()) {
            log.warn("{} trabajos de audio interrumpidos volvieron a la cola al arrancar", interrupted.size());
        }
        return interrupted.size();
    }

    private List<StageContext> stagesOf(AiExtraction extraction, List<UUID> organizationIds) {
        // El parte de una casa sólo propone sobre las etapas de esa casa.
        return stageCatalog
                .stagesForScope(extraction.getDevelopmentId(), extraction.getUnitId(), organizationIds)
                .stream()
                .map(s -> new StageContext(s.id(), s.name(), s.orderIndex(), s.status()))
                .toList();
    }
}
