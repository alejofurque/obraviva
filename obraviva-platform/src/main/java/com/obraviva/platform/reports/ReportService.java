package com.obraviva.platform.reports;

import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.DevelopmentAccessService.DevelopmentSummary;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.reports.ReportDtos.ApproveReportRequest;
import com.obraviva.platform.reports.ReportDtos.GenerateReportRequest;
import com.obraviva.platform.reports.ReportDtos.RejectReportRequest;
import com.obraviva.platform.reports.ReportDtos.ReportDetailResponse;
import com.obraviva.platform.reports.ReportDtos.ReportSummaryResponse;
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
 * Alta de reportes de período, consulta, fronteras transaccionales del worker de
 * redacción y flujo de revisión humana.
 *
 * <p>La composición del snapshot ocurre acá, de forma sincrónica: son consultas a la
 * base, no I/O externo, y resolverlas dentro de la petición permite rechazar un
 * período sin datos antes de crear nada. Lo que se delega a la cola es la redacción,
 * que depende de un servicio externo y puede tardar.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** Pasos que el worker de reportes sabe ejecutar. */
    private static final Set<PipelineStep> SUPPORTED_STEPS = Set.of(PipelineStep.NARRATE);

    private final ReportRepository reportRepository;
    private final ReportComposer composer;
    private final ReportDataCodec codec;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final ProcessingJobService jobService;
    private final int maxAttempts;

    public ReportService(
            ReportRepository reportRepository,
            ReportComposer composer,
            ReportDataCodec codec,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            ProcessingJobService jobService,
            @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.reportRepository = reportRepository;
        this.composer = composer;
        this.codec = codec;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.jobService = jobService;
        this.maxAttempts = maxAttempts;
    }

    // --- Alta desde la web ---

    @Transactional
    public ReportDetailResponse generate(
            UUID userId, UUID developmentId, GenerateReportRequest request) {

        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El fin del período es anterior a su inicio");
        }

        DevelopmentSummary development =
                developmentAccess.requireOwnedDevelopment(userId, developmentId);
        List<UUID> organizationIds = membershipService.organizationIdsOf(userId);

        ReportData data = composer.compose(
                development, organizationIds, request.periodStart(), request.periodEnd());

        if (data.isEmpty()) {
            // Sin datos no hay reporte. Pedirle un texto a un modelo de lenguaje sobre
            // un período vacío es la forma más directa de conseguir afirmaciones
            // inventadas.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El período no tiene avances ni capturas publicados: no hay nada que reportar");
        }

        Report report = reportRepository.save(new Report(
                development.organizationId(),
                developmentId,
                request.periodStart(),
                request.periodEnd(),
                codec.encode(data),
                userId));

        jobService.create(development.organizationId(), JobType.REPORT_GENERATION, report.getId());
        // El snapshot ya está: no hay archivo que esperar, así que el trabajo entra a
        // la cola en el mismo acto.
        jobService.markReady(report.getId());

        log.info("Reporte {} compuesto para el desarrollo {} ({} a {}): {} avances, {} capturas",
                report.getId(), developmentId, request.periodStart(), request.periodEnd(),
                data.totals().progressEntries(), data.totals().captures());

        return toDetail(report, data);
    }

    // --- Consulta ---

    @Transactional(readOnly = true)
    public ReportDetailResponse detail(UUID userId, UUID reportId) {
        Report report = requireAccessible(userId, reportId);
        return toDetail(report, codec.decode(report.getSourceData()));
    }

    @Transactional(readOnly = true)
    public List<ReportSummaryResponse> listByDevelopment(UUID userId, UUID developmentId) {
        developmentAccess.requireOwnerAccess(userId, developmentId);
        return reportRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByPeriodStartDesc(
                        developmentId, membershipService.organizationIdsOf(userId))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    /** Bandeja del revisor: todo lo redactado que espera decisión, de cualquier obra. */
    @Transactional(readOnly = true)
    public List<ReportSummaryResponse> listPendingReview(UUID userId) {
        return reportRepository
                .findByOrganizationIdInAndStatusOrderByCreatedAtAsc(
                        membershipService.ownedOrganizationIdsOf(userId), ReportStatus.READY_FOR_REVIEW)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    // --- Revisión humana ---

    /**
     * Aprueba el reporte con el texto que el revisor dejó.
     *
     * <p>Ese texto no pasa por la salvaguarda de cifras: vigila al modelo, no a la
     * persona. Lo que sí ocurre es que reemplaza al del modelo, que queda archivado en
     * {@code rawOutput}: lo que se emite es lo aprobado.
     */
    @Transactional
    public ReportDetailResponse approve(
            UUID userId, UUID reportId, ApproveReportRequest request) {
        Report report = requireReviewable(userId, reportId);

        report.replaceNarrative(request.narrative().trim());
        report.approve(userId, request.notes());
        closeJob(reportId);

        log.info("Reporte {} aprobado por {}", reportId, userId);
        return toDetail(report, codec.decode(report.getSourceData()));
    }

    @Transactional
    public ReportDetailResponse reject(
            UUID userId, UUID reportId, RejectReportRequest request) {
        Report report = requireReviewable(userId, reportId);

        report.reject(userId, request.notes());
        closeJob(reportId);

        log.info("Reporte {} rechazado por {}", reportId, userId);
        return toDetail(report, codec.decode(report.getSourceData()));
    }

    /**
     * El trabajo se cierra como completado tanto si el reporte se aprueba como si se
     * rechaza: el pipeline llegó a su fin en ambos casos. Marcarlo fallido ante un
     * rechazo confundiría una decisión editorial con un error del sistema, y esa
     * distinción es la que hace útil el estado FAILED.
     */
    private void closeJob(UUID reportId) {
        jobService.findByResource(reportId)
                .filter(job -> job.getStatus() == JobStatus.NEEDS_REVIEW)
                .ifPresent(job -> jobService.finishPipeline(job.getId()));
    }

    // --- Fronteras transaccionales del worker ---

    /**
     * Toma el próximo trabajo de redacción y devuelve todo lo que el worker necesita.
     *
     * <p>El reporte se busca por identificador sin acotar por organización: es un
     * proceso de fondo, no hay usuario, y la pertenencia ya está garantizada por el
     * trabajo que se acaba de reclamar.
     */
    @Transactional
    public Optional<ReportNarrationTask> claimNext() {
        return jobService.claimNext(JobType.REPORT_GENERATION, SUPPORTED_STEPS)
                .flatMap(job -> reportRepository.findById(job.getResourceId())
                        .map(report -> new ReportNarrationTask(
                                job.getId(),
                                report.getId(),
                                job.getPipelineStep(),
                                codec.decode(report.getSourceData()))));
    }

    /** La redacción terminó: el reporte espera aprobación y sale de la cola. */
    @Transactional
    public void recordNarration(
            UUID jobId,
            UUID reportId,
            String narrative,
            String rawOutput,
            String promptVersion,
            String modelId) {
        reportRepository.findById(reportId).ifPresent(report ->
                report.markReadyForReview(narrative, rawOutput, promptVersion, modelId));
        jobService.awaitHumanReview(jobId);
    }

    /**
     * Fallo técnico: el trabajo vuelve a la cola. Si se agotaron los intentos, el
     * reporte queda marcado como fallido y no como pendiente para siempre.
     */
    @Transactional
    public void recordTechnicalFailure(UUID jobId, UUID reportId, String error) {
        jobService.retryOrFail(jobId, error, maxAttempts);
        failReportIfJobDied(jobId, reportId, error);
    }

    /**
     * Guarda la respuesta cruda antes de reencolar. El diseño anterior del módulo de
     * audio perdía la respuesta justo cuando más se necesitaba; acá se conserva desde
     * el primer día.
     */
    @Transactional
    public void recordFailedOutput(UUID jobId, UUID reportId, String rawOutput, String error) {
        reportRepository.findById(reportId).ifPresent(report -> report.attachRawOutput(rawOutput));
        jobService.retryOrFail(jobId, error, maxAttempts);
        failReportIfJobDied(jobId, reportId, error);
    }

    private void failReportIfJobDied(UUID jobId, UUID reportId, String error) {
        Optional<ProcessingJob> job = jobService.findByResource(reportId);
        if (job.isEmpty() || job.get().getStatus() != JobStatus.FAILED) {
            return;
        }
        reportRepository.findById(reportId).ifPresent(report -> report.markFailed(error));
        log.error("El reporte {} quedó fallido: se agotaron los intentos del trabajo {}",
                reportId, jobId);
    }

    // --- Interno ---

    /**
     * El reporte narra avance y gasto para inversores: es información económica y
     * queda para el dueño. Ajeno responde 404; accesible pero de encargado, 403.
     */
    private Report requireAccessible(UUID userId, UUID reportId) {
        Report report = reportRepository
                .findByIdAndOrganizationIdIn(reportId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Reporte no encontrado"));
        developmentAccess.requireOwnerOf(userId, report.getOrganizationId());
        return report;
    }

    /**
     * Un reporte sólo se resuelve desde READY_FOR_REVIEW. El estado se verifica acá y
     * no se deja llegar a la entidad: su excepción de transición inválida es una red
     * de último recurso y se traduciría en un 500, cuando esto es un conflicto de
     * estado que el cliente tiene que poder mostrar.
     */
    private Report requireReviewable(UUID userId, UUID reportId) {
        Report report = requireAccessible(userId, reportId);
        if (!report.getStatus().isReviewable()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El reporte no está en revisión (estado actual: " + report.getStatus() + ")");
        }
        return report;
    }

    private ReportDetailResponse toDetail(Report report, ReportData data) {
        return new ReportDetailResponse(
                report.getId(),
                report.getDevelopmentId(),
                report.getStatus().name(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                data,
                report.getNarrative(),
                report.getPromptVersion(),
                report.getModelId(),
                report.getCreatedAt(),
                report.getReviewedBy(),
                report.getReviewedAt(),
                report.getReviewNotes());
    }

    private ReportSummaryResponse toSummary(Report report) {
        ReportData data = codec.decode(report.getSourceData());
        return new ReportSummaryResponse(
                report.getId(),
                report.getDevelopmentId(),
                report.getStatus().name(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                data.totals().progressEntries(),
                data.totals().captures(),
                report.getCreatedAt());
    }
}