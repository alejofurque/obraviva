package com.obraviva.platform.reports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reporte de un período de obra, pendiente de aprobación.
 *
 * <p>Materializa el principio rector del documento maestro en su versión de
 * redacción: el motor de datos compone {@code sourceData}, el modelo de lenguaje
 * produce {@code narrative} a partir de ese snapshot y nada más, y el desarrollista
 * aprueba antes de que el reporte se considere emitido.
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** Snapshot del período, serializado como JSON. Ver el comentario de la migración V14. */
    @Column(name = "source_data", nullable = false, columnDefinition = "text")
    private String sourceData;

    @Column(columnDefinition = "text")
    private String narrative;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "raw_output", columnDefinition = "text")
    private String rawOutput;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes")
    private String reviewNotes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Report() {
    }

    public Report(
            UUID organizationId,
            UUID developmentId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String sourceData,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.sourceData = sourceData;
        this.createdBy = createdBy;
        this.status = ReportStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    private void transitionTo(ReportStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Transición de reporte inválida: %s → %s".formatted(status, target));
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /** La redacción terminó: el texto queda a la espera de aprobación. */
    public void markReadyForReview(
            String narrative, String rawOutput, String promptVersion, String modelId) {
        this.narrative = narrative;
        this.rawOutput = rawOutput;
        this.promptVersion = promptVersion;
        this.modelId = modelId;
        transitionTo(ReportStatus.READY_FOR_REVIEW);
    }

    /** Guarda la respuesta cruda del modelo aunque no haya podido usarse. */
    public void attachRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
        this.updatedAt = Instant.now();
    }

    /**
     * Reemplaza el texto por el que el revisor dejó. Lo que queda registrado es lo
     * aprobado; la versión original del modelo permanece en {@code rawOutput}.
     */
    public void replaceNarrative(String narrative) {
        this.narrative = narrative;
        this.updatedAt = Instant.now();
    }

    public void approve(UUID reviewer, String notes) {
        transitionTo(ReportStatus.APPROVED);
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = truncate(notes);
    }

    public void reject(UUID reviewer, String notes) {
        transitionTo(ReportStatus.REJECTED);
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = truncate(notes);
    }

    public void markFailed(String reason) {
        transitionTo(ReportStatus.FAILED);
        this.reviewNotes = truncate(reason);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getDevelopmentId() {
        return developmentId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getSourceData() {
        return sourceData;
    }

    public String getNarrative() {
        return narrative;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModelId() {
        return modelId;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}