package com.obraviva.platform.progress;

import com.obraviva.platform.developments.StageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "progress_entries")
public class ProgressEntry {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    /** Etapa asociada, opcional: un avance puede no corresponder a ninguna. */
    @Column(name = "stage_id")
    private UUID stageId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProgressSource source;

    @Column(nullable = false)
    private int version;

    /** Identifica el hilo de versiones: la v1 es su propio root. */
    @Column(name = "root_id", nullable = false)
    private UUID rootId;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Casa del avance. Nula: novedad de las obras comunes del desarrollo. */
    @Column(name = "unit_id")
    private UUID unitId;

    // --- Cambio de etapa que trajo el avance (V32); nulos si no tocó la etapa ---

    @Column(name = "stage_percent_before")
    private Integer stagePercentBefore;

    @Column(name = "stage_percent_after")
    private Integer stagePercentAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_status_before")
    private StageStatus stageStatusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_status_after")
    private StageStatus stageStatusAfter;

    protected ProgressEntry() {
    }

    /** Primera versión de un avance nuevo. */
    public ProgressEntry(
            UUID organizationId,
            UUID developmentId,
            UUID stageId,
            LocalDate entryDate,
            String description,
            ProgressSource source,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.stageId = stageId;
        this.entryDate = entryDate;
        this.description = description;
        this.source = source;
        this.version = 1;
        this.rootId = this.id;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * Crea la versión siguiente de este avance. No modifica el contenido
     * de la versión actual: la marca como superada y devuelve una nueva.
     */
    public ProgressEntry createNextVersion(
            UUID stageId, LocalDate entryDate, String description, UUID createdBy) {
        ProgressEntry next = new ProgressEntry(
                this.organizationId,
                this.developmentId,
                stageId,
                entryDate,
                description,
                this.source,
                createdBy);
        next.rootId = this.rootId;
        next.version = this.version + 1;
        next.unitId = this.unitId;
        this.supersededAt = Instant.now();
        return next;
    }

    public void assignUnit(UUID unitId) {
        this.unitId = unitId;
    }

    /** Lo que el avance le hizo a su etapa: porcentaje y estado de antes y de después. */
    public void recordStageChange(int percentBefore, int percentAfter, StageStatus statusBefore, StageStatus statusAfter) {
        this.stagePercentBefore = percentBefore;
        this.stagePercentAfter = percentAfter;
        this.stageStatusBefore = statusBefore;
        this.stageStatusAfter = statusAfter;
    }

    public Integer getStagePercentBefore() {
        return stagePercentBefore;
    }

    public Integer getStagePercentAfter() {
        return stagePercentAfter;
    }

    public StageStatus getStageStatusBefore() {
        return stageStatusBefore;
    }

    public StageStatus getStageStatusAfter() {
        return stageStatusAfter;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public boolean isCurrent() {
        return supersededAt == null;
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

    public UUID getStageId() {
        return stageId;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public String getDescription() {
        return description;
    }

    public ProgressSource getSource() {
        return source;
    }

    public int getVersion() {
        return version;
    }

    public UUID getRootId() {
        return rootId;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}