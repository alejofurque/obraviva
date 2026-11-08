package com.obraviva.platform.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pipeline_step", nullable = false)
    private PipelineStep pipelineStep;

    /** Referencia genérica al recurso procesado (por ejemplo, una captura). */
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    /** Identificador del trabajo en el servicio externo, cuando aplica. */
    @Column(name = "external_reference")
    private String externalReference;

    /** Tomas del paso actual, no del pipeline completo. Se reinicia al avanzar. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Momento en que el trabajo entró al paso actual. Distinto de {@code updatedAt}:
     * la espera del servicio externo se resuelve con muchas consultas y cada una
     * mueve {@code updatedAt}, así que ese campo no sirve para medir la duración de
     * un paso.
     */
    @Column(name = "step_started_at", nullable = false)
    private Instant stepStartedAt;

    protected ProcessingJob() {
    }

    public ProcessingJob(UUID organizationId, JobType jobType, UUID resourceId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.jobType = jobType;
        this.resourceId = resourceId;
        this.status = JobStatus.UPLOADED;
        this.pipelineStep = PipelineStep.initialFor(jobType);
        this.attempts = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.stepStartedAt = this.createdAt;
    }

    public void transitionTo(JobStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Transición inválida: %s → %s".formatted(status, target));
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /**
     * Toma el trabajo para un paso ejecutado por la propia plataforma, sin servicio
     * externo involucrado todavía. La referencia externa se asigna recién cuando se
     * delega en un tercero.
     */
    public void markProcessing() {
        markProcessing(null);
    }

    public void markProcessing(String externalReference) {
        transitionTo(JobStatus.PROCESSING);
        if (externalReference != null) {
            this.externalReference = externalReference;
        }
        this.attempts++;
    }

    public void completeStep(PipelineStep next) {
        completeStep(next, null);
    }

    /**
     * El paso actual terminó bien: el trabajo vuelve a la cola posicionado en el
     * siguiente. La referencia externa se registra en la misma operación cuando el
     * paso delegó trabajo en un tercero, para que no queden dos escrituras separadas
     * que puedan desincronizarse.
     *
     * <p>El reinicio de {@code attempts} es imprescindible, no cosmético. Cada toma
     * incrementa el contador, así que sin reiniciarlo un trabajo que atraviesa varios
     * pasos exitosos agotaría los reintentos permitidos sin haber fallado nunca.
     */
    public void completeStep(PipelineStep next, String externalReference) {
        if (next == null || !pipelineStep.canAdvanceTo(next)) {
            throw new IllegalStateException(
                    "Avance de paso inválido: %s → %s".formatted(pipelineStep, next));
        }
        transitionTo(JobStatus.PENDING);
        this.pipelineStep = next;
        if (externalReference != null) {
            this.externalReference = externalReference;
        }
        this.attempts = 0;
        this.lastError = null;
        this.stepStartedAt = Instant.now();
    }

    /**
     * El último paso del pipeline terminó: el trabajo avanza a {@code DONE} y queda
     * cerrado, en una sola operación.
     *
     * <p>Existe separado de {@link #completeStep} porque aquel deja el trabajo en
     * {@code PENDING} para que lo tome el worker del paso siguiente, y acá no hay
     * siguiente. Encadenar {@code completeStep} con un cierre posterior no funciona:
     * la máquina de estados no admite {@code PENDING → COMPLETED}, y con razón — un
     * trabajo en cola no debe poder declararse terminado sin pasar por procesamiento.
     * El cierre ocurre mientras el trabajo todavía está tomado, que es además lo
     * semánticamente correcto.
     */
    public void finishPipeline() {
        if (!pipelineStep.canAdvanceTo(PipelineStep.DONE)) {
            throw new IllegalStateException(
                    "El pipeline no puede finalizar desde el paso %s".formatted(pipelineStep));
        }
        transitionTo(JobStatus.COMPLETED);
        this.pipelineStep = PipelineStep.DONE;
        this.attempts = 0;
        this.lastError = null;
        this.stepStartedAt = Instant.now();
    }

        /**
     * La interpretación terminó y espera confirmación humana.
     *
     * <p>El trabajo pasa a {@code NEEDS_REVIEW} y no a {@code PENDING}, que es el
     * estado que la migración V9 ya anticipaba para los trabajos de inteligencia
     * artificial. La consecuencia es mecánica y buscada: el reclamo de la cola
     * filtra por {@code PENDING}, de modo que un trabajo en revisión queda fuera
     * sin necesidad de ningún filtro adicional. Espera a una persona, no a un
     * worker.
     */
    public void awaitHumanReview() {
        if (!pipelineStep.canAdvanceTo(PipelineStep.AWAIT_REVIEW)) {
            throw new IllegalStateException(
                    "No se puede pasar a revisión desde el paso %s".formatted(pipelineStep));
        }
        transitionTo(JobStatus.NEEDS_REVIEW);
        this.pipelineStep = PipelineStep.AWAIT_REVIEW;
        this.attempts = 0;
        this.lastError = null;
        this.stepStartedAt = Instant.now();
    }

    /**
     * El paso todavía no puede completarse porque depende de un tercero que sigue
     * trabajando. Vuelve a la cola sin avanzar y sin contar como fallo: la consulta
     * se respondió bien, sólo que la respuesta fue "esperá".
     *
     * <p>No toca {@code stepStartedAt}, de modo que el tiempo total en el paso se
     * sigue midiendo desde que se entró en él.
     */
    public void awaitAgain() {
        transitionTo(JobStatus.PENDING);
        this.attempts = 0;
        this.lastError = null;
    }

    public void markFailed(String error) {
        transitionTo(JobStatus.FAILED);
        this.lastError = truncate(error);
    }

    /** Devuelve el trabajo a la cola tras un fallo transitorio, sin avanzar de paso. */
    public void requeue(String error) {
        transitionTo(JobStatus.PENDING);
        this.lastError = truncate(error);
    }

    public Duration timeInCurrentStep() {
        return Duration.between(stepStartedAt, Instant.now());
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

    public JobType getJobType() {
        return jobType;
    }

    public JobStatus getStatus() {
        return status;
    }

    public PipelineStep getPipelineStep() {
        return pipelineStep;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStepStartedAt() {
        return stepStartedAt;
    }
}