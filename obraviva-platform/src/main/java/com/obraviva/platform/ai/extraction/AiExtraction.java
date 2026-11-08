package com.obraviva.platform.ai.extraction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Interpretación de un audio del encargado de obra, pendiente de confirmación.
 *
 * <p>Es la pieza que materializa el principio rector del documento maestro: lo que
 * produce el modelo de lenguaje vive acá, visible para el revisor, hasta que una
 * persona lo aprueba. Sólo entonces se convierte en avances publicados.
 *
 * <p>Se conservan la transcripción, la salida cruda del modelo, la versión de
 * prompt y el identificador de modelo. Sin eso, ante una propuesta discutible no
 * habría forma de saber si el problema estuvo en lo que el encargado dijo, en lo
 * que la transcripción entendió o en cómo el modelo lo interpretó.
 */
@Entity
@Table(name = "ai_extractions")
public class AiExtraction {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    /** Casa sobre la que se publica el parte. Nula: obras comunes. */
    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "audio_storage_key", nullable = false)
    private String audioStorageKey;

    @Column(columnDefinition = "text")
    private String transcript;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "raw_output", columnDefinition = "text")
    private String rawOutput;

    /** Propuesta validada, serializada como JSON. Ver el comentario de la migración V13. */
    @Column(columnDefinition = "text")
    private String proposal;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

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

    protected AiExtraction() {
    }

    public AiExtraction(
            UUID organizationId,
            UUID developmentId,
            String audioExtension,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.createdBy = createdBy;
        this.reviewStatus = ReviewStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        // La clave se genera en la entidad, igual que en las capturas: la
        // convención de nombres vive en un solo lugar y ningún worker la reconstruye.
        this.audioStorageKey = "developments/%s/audios/%s.%s"
                .formatted(developmentId, this.id, audioExtension);
    }

    private void transitionTo(ReviewStatus target) {
        if (!reviewStatus.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Transición de revisión inválida: %s → %s".formatted(reviewStatus, target));
        }
        this.reviewStatus = target;
        this.updatedAt = Instant.now();
    }

    /**
     * El procesamiento terminó: la propuesta queda a la espera de revisión humana.
     */
    public void markReadyForReview(
            String transcript, String proposal, String rawOutput,
            String promptVersion, String modelId) {
        this.transcript = transcript;
        this.proposal = proposal;
        this.rawOutput = rawOutput;
        this.promptVersion = promptVersion;
        this.modelId = modelId;
        transitionTo(ReviewStatus.READY_FOR_REVIEW);
    }

    /**
     * La transcripción se guarda apenas está disponible, antes de la interpretación.
     * Si el modelo de lenguaje falla después, el trabajo del transcriptor no se
     * pierde y el revisor conserva algo que mirar.
     */
    public void attachTranscript(String transcript) {
        this.transcript = transcript;
        this.updatedAt = Instant.now();
    }
        /**
     * Guarda la respuesta cruda del modelo aunque no se haya podido interpretar.
     * Es el material de diagnóstico cuando algo falla.
     */
    public void attachRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
        this.updatedAt = Instant.now();
    }

        /**
     * Reemplaza la propuesta almacenada por la que el revisor aprobó. Lo que queda
     * registrado es lo publicado, no lo que el modelo había propuesto; la versión
     * original del modelo permanece en {@code rawOutput}.
     */
    public void replaceProposal(String proposal) {
        this.proposal = proposal;
        this.updatedAt = Instant.now();
    }

    public void approve(UUID reviewer, String notes) {
        transitionTo(ReviewStatus.APPROVED);
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = truncate(notes);
    }

    public void reject(UUID reviewer, String notes) {
        transitionTo(ReviewStatus.REJECTED);
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewNotes = truncate(notes);
    }

    public void markFailed(String reason) {
        transitionTo(ReviewStatus.FAILED);
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

    public void assignUnit(UUID unitId) {
        this.unitId = unitId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getDevelopmentId() {
        return developmentId;
    }

    public String getAudioStorageKey() {
        return audioStorageKey;
    }

    public String getTranscript() {
        return transcript;
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

    public String getProposal() {
        return proposal;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
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