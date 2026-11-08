package com.obraviva.platform.ai;

import com.obraviva.platform.ai.AudioDtos.ApprovalResultResponse;
import com.obraviva.platform.ai.AudioDtos.ApproveExtractionRequest;
import com.obraviva.platform.ai.AudioDtos.AudioExtractionJobResponse;
import com.obraviva.platform.ai.AudioDtos.CreateAudioExtractionRequest;
import com.obraviva.platform.ai.AudioDtos.ExtractionDetailResponse;
import com.obraviva.platform.ai.AudioDtos.ExtractionSummaryResponse;
import com.obraviva.platform.ai.AudioDtos.RejectExtractionRequest;
import com.obraviva.platform.ai.extraction.AiExtraction;
import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.extraction.ConfidenceLevel;
import com.obraviva.platform.ai.extraction.ExtractionProposal;
import com.obraviva.platform.ai.extraction.ProgressNote;
import com.obraviva.platform.ai.extraction.ProposalCodec;
import com.obraviva.platform.ai.extraction.ReviewStatus;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.ai.extraction.StageProposal;
import com.obraviva.platform.ai.extraction.SupplyAlert;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.progress.ProgressSource;
import com.obraviva.platform.publication.ProgressPublicationService;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Alta de interpretaciones de audio, fronteras transaccionales del worker y flujo
 * de revisión humana.
 */
@Service
public class AudioExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AudioExtractionService.class);

    /** Pasos que el worker de audio sabe ejecutar. */
    private static final Set<PipelineStep> SUPPORTED_STEPS =
            Set.of(PipelineStep.TRANSCRIBE, PipelineStep.INTERPRET);

    private static final int TRANSCRIPT_PREVIEW_LENGTH = 160;

    private static final String INTERRUPTED_MESSAGE = "El proceso se interrumpió a mitad del paso";

    private static final String RETRIES_EXHAUSTED_MESSAGE =
            "No se pudo procesar el audio después de varios intentos. "
                    + "Podés volver a subirlo o cargar el parte a mano.";

    private final AiExtractionRepository extractionRepository;
    private final StorageService storageService;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final StageCatalogService stageCatalog;
    private final ProcessingJobService jobService;
    private final ProgressPublicationService publicationService;
    private final UnitCatalogService unitCatalog;
    private final ProposalCodec proposalCodec;
    private final int maxAttempts;

    public AudioExtractionService(
            AiExtractionRepository extractionRepository,
            StorageService storageService,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            StageCatalogService stageCatalog,
            ProcessingJobService jobService,
            ProgressPublicationService publicationService,
            UnitCatalogService unitCatalog,
            ProposalCodec proposalCodec,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.extractionRepository = extractionRepository;
        this.storageService = storageService;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.stageCatalog = stageCatalog;
        this.jobService = jobService;
        this.publicationService = publicationService;
        this.unitCatalog = unitCatalog;
        this.proposalCodec = proposalCodec;
        this.maxAttempts = maxAttempts;
    }

    // --- Alta desde la web ---

    @Transactional
    public AudioExtractionJobResponse createFromAudio(
            UUID userId, UUID developmentId, CreateAudioExtractionRequest request) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);

        if (!request.audioContentType().startsWith("audio/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El archivo debe ser un audio");
        }

        if (request.unitId() != null && unitCatalog.findInOrganization(request.unitId(), organizationId)
                .filter(unit -> unit.developmentId().equals(developmentId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
        AiExtraction pending = new AiExtraction(
                organizationId, developmentId, extensionOf(request.audioFilename()), userId);
        pending.assignUnit(request.unitId());
        AiExtraction extraction = extractionRepository.save(pending);

        ProcessingJob job = jobService.create(
                organizationId, JobType.AUDIO_EXTRACTION, extraction.getId());

        String uploadUrl = storageService.presignUpload(
                extraction.getAudioStorageKey(), request.audioContentType());

        return new AudioExtractionJobResponse(
                extraction.getId(),
                job.getId(),
                extraction.getReviewStatus().name(),
                uploadUrl,
                request.audioContentType());
    }

    @Transactional
    public AudioExtractionJobResponse confirmAudioUpload(UUID userId, UUID extractionId) {
        AiExtraction extraction = requireWorkable(userId, extractionId);

        if (!storageService.exists(extraction.getAudioStorageKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El audio no se encuentra en el almacenamiento");
        }

        jobService.markReady(extractionId);
        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();

        return new AudioExtractionJobResponse(
                extractionId, job.getId(), extraction.getReviewStatus().name(), null, null);
    }

    // --- Revisión ---

    @Transactional(readOnly = true)
    public ExtractionDetailResponse detail(UUID userId, UUID extractionId) {
        return toDetail(requireWorkable(userId, extractionId));
    }

    @Transactional(readOnly = true)
    public List<ExtractionSummaryResponse> listByDevelopment(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        // Los audios son trabajo por revisar, no material para mirar: el encargado de casas
        // puntuales ve sólo los de sus casas, igual que el detalle (requireWorkable).
        java.util.function.Predicate<UUID> workable = developmentAccess.workableUnits(userId, developmentId, organizationId);
        return summaries(userId, extractionRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtDesc(
                        developmentId, membershipService.organizationIdsOf(userId))
                .stream()
                .filter(extraction -> workable.test(extraction.getUnitId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<ExtractionSummaryResponse> listPendingReview(UUID userId) {
        java.util.Set<UUID> visible = developmentAccess.visibleDevelopmentIds(userId);
        return summaries(userId, extractionRepository
                .findByOrganizationIdInAndReviewStatusOrderByCreatedAtAsc(
                        membershipService.organizationIdsOf(userId), ReviewStatus.READY_FOR_REVIEW)
                .stream()
                .filter(extraction -> visible.contains(extraction.getDevelopmentId()))
                .filter(extraction -> developmentAccess.canWorkOnUnit(
                        userId, extraction.getDevelopmentId(), extraction.getOrganizationId(), extraction.getUnitId()))
                .toList());
    }

    /**
     * Publica lo que el revisor dejó, por la misma publicación atómica que "Publicar
     * avance": cada entrada es un avance de origen audio, con su cambio de etapa y sus
     * fotos. Todo en esta transacción: si un avance no pasa las validaciones, no se
     * publica ninguno y la interpretación sigue en revisión.
     */
    @Transactional
    public ApprovalResultResponse approve(
            UUID userId, UUID extractionId, ApproveExtractionRequest request) {
        AiExtraction extraction = requireReviewable(userId, extractionId);

        if (!extraction.getReviewStatus().isReviewable()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La interpretación no está en revisión (estado actual: "
                            + extraction.getReviewStatus() + ")");
        }

        List<CreateProgressEntryRequest> entries = request.entries().stream()
                .map(entry -> new CreateProgressEntryRequest(
                        entry.stageId(),
                        request.entryDate(),
                        entry.description().trim(),
                        extraction.getUnitId(),
                        entry.photoIds(),
                        entry.stageUpdate(),
                        entry.photoSectors()))
                .toList();
        publicationService.publishAll(userId, extraction.getDevelopmentId(), entries, ProgressSource.AUDIO);

        // La propuesta almacenada se reemplaza por la que efectivamente se publicó: lo
        // que queda registrado es lo que el revisor aprobó, no lo que el modelo había
        // propuesto. La versión original permanece en raw_output; las alertas de
        // insumos, que no se publican, se conservan de la propuesta guardada.
        List<StageProposal> stageChanges = request.entries().stream()
                .filter(entry -> entry.stageUpdate() != null && entry.stageUpdate().status() != null)
                .map(entry -> new StageProposal(entry.stageId(), entry.stageUpdate().status().name(),
                        entry.description().trim(), ConfidenceLevel.HIGH))
                .toList();
        List<ProgressNote> notes = request.entries().stream()
                .map(entry -> new ProgressNote(entry.stageId(), entry.description().trim(), ConfidenceLevel.HIGH))
                .toList();
        List<SupplyAlert> alerts = proposalCodec.decode(extraction.getProposal()).supplyAlerts();
        extraction.replaceProposal(proposalCodec.encode(new ExtractionProposal(stageChanges, notes, alerts)));
        extraction.approve(userId, request.notes());

        long updatedStages = request.entries().stream().filter(entry -> entry.stageUpdate() != null).count();
        log.info("Interpretación {} aprobada por {}: {} avances publicados, {} etapas actualizadas",
                extractionId, userId, entries.size(), updatedStages);

        return new ApprovalResultResponse(
                extractionId,
                extraction.getReviewStatus().name(),
                entries.size(),
                (int) updatedStages);
    }

    @Transactional
    public ExtractionDetailResponse reject(
            UUID userId, UUID extractionId, RejectExtractionRequest request) {
        AiExtraction extraction = requireReviewable(userId, extractionId);

        if (!extraction.getReviewStatus().isReviewable()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La interpretación no está en revisión (estado actual: "
                            + extraction.getReviewStatus() + ")");
        }

        extraction.reject(userId, request.notes());
        jobService.findByResource(extractionId)
                .ifPresent(job -> jobService.fail(job.getId(), "Descartada por el revisor"));

        log.info("Interpretación {} rechazada por {}", extractionId, userId);
        return toDetail(extraction);
    }

    // --- Fronteras transaccionales del worker ---

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

    // --- Común ---

    private List<StageContext> stagesOf(AiExtraction extraction, List<UUID> organizationIds) {
        // El parte de una casa sólo propone sobre las etapas de esa casa.
        return stageCatalog
                .stagesForScope(extraction.getDevelopmentId(), extraction.getUnitId(), organizationIds)
                .stream()
                .map(s -> new StageContext(s.id(), s.name(), s.orderIndex(), s.status()))
                .toList();
    }

    private ExtractionDetailResponse toDetail(AiExtraction extraction) {
        return new ExtractionDetailResponse(
                extraction.getId(),
                extraction.getDevelopmentId(),
                extraction.getReviewStatus().name(),
                extraction.getTranscript(),
                proposalCodec.decode(extraction.getProposal()),
                extraction.getPromptVersion(),
                extraction.getModelId(),
                storageService.presignDownload(extraction.getAudioStorageKey()),
                extraction.getCreatedAt(),
                extraction.getReviewedBy(),
                extraction.getReviewedAt(),
                extraction.getReviewNotes(),
                extraction.getUnitId());
    }

    /** Resúmenes con el estado de su trabajo, buscado en una sola consulta. */
    private List<ExtractionSummaryResponse> summaries(UUID viewerId, List<AiExtraction> extractions) {
        if (extractions.isEmpty()) {
            return List.of();
        }
        java.util.Map<UUID, ProcessingJob> jobs = jobService.findByResources(
                extractions.stream().map(AiExtraction::getOrganizationId).distinct().toList(),
                extractions.stream().map(AiExtraction::getId).toList());
        return extractions.stream()
                .map(extraction -> toSummary(extraction, jobs.get(extraction.getId()), viewerId))
                .toList();
    }

    private ExtractionSummaryResponse toSummary(AiExtraction extraction, ProcessingJob job, UUID viewerId) {
        String transcript = extraction.getTranscript();
        String preview = (transcript == null) ? null
                : transcript.length() <= TRANSCRIPT_PREVIEW_LENGTH
                        ? transcript
                        : transcript.substring(0, TRANSCRIPT_PREVIEW_LENGTH) + "...";

        return new ExtractionSummaryResponse(
                extraction.getId(),
                extraction.getDevelopmentId(),
                extraction.getReviewStatus().name(),
                preview,
                proposalCodec.decode(extraction.getProposal()).elementCount(),
                extraction.getCreatedAt(),
                extraction.getUnitId(),
                job == null ? null : job.getStatus().name(),
                job == null ? null : job.getPipelineStep().name(),
                extraction.getReviewNotes(),
                extraction.getCreatedBy().equals(viewerId));
    }

    /**
     * Para lo que se hace sobre el audio de una casa: llegar al desarrollo no alcanza.
     * Un encargado de casas puntuales recibe 403 en las demás y en las obras comunes,
     * con el mismo mensaje que el resto de las cargas.
     */
    private AiExtraction requireWorkable(UUID userId, UUID extractionId) {
        AiExtraction extraction = requireAccessible(userId, extractionId);
        if (!developmentAccess.canWorkOnUnit(
                userId, extraction.getDevelopmentId(), extraction.getOrganizationId(), extraction.getUnitId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, extraction.getUnitId() == null
                    ? "Las obras comunes las carga un encargado de todo el desarrollo"
                    : "No estás asignado a esta casa");
        }
        return extraction;
    }

    /**
     * Aprobar o descartar: cada encargado, sólo sus audios; el dueño, todos. Ver el audio de
     * otro encargado de la casa sigue permitido; decidir sobre él, no (403).
     */
    private AiExtraction requireReviewable(UUID userId, UUID extractionId) {
        AiExtraction extraction = requireWorkable(userId, extractionId);
        if (!extraction.getCreatedBy().equals(userId)
                && !membershipService.isOwnerOf(userId, extraction.getOrganizationId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este audio lo revisa quien lo envió o el dueño");
        }
        return extraction;
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

    private AiExtraction requireAccessible(UUID userId, UUID extractionId) {
        return extractionRepository
                .findByIdAndOrganizationIdIn(extractionId, membershipService.organizationIdsOf(userId))
                .filter(extraction -> developmentAccess.canAccess(userId, extraction.getDevelopmentId(), extraction.getOrganizationId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Interpretación no encontrada"));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase()
                : "ogg";
    }
}