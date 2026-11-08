package com.obraviva.platform.captures3d;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "captures_3d")
public class Capture3d {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "stage_id")
    private UUID stageId;

    @Column(name = "capture_date", nullable = false)
    private LocalDate captureDate;

    @Column
    private String description;

    /** Ambiente que registra la captura. Opcional: sin ambiente sigue siendo navegable. */
    @Column
    private String zone;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "backup_storage_key")
    private String backupStorageKey;

    @Column(name = "video_storage_key")
    private String videoStorageKey;

    @Column(name = "normalized_video_storage_key")
    private String normalizedVideoStorageKey;

    @Column(name = "result_storage_key")
    private String resultStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaptureStatus status;

    @Embedded
    private SpatialTransform transform;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected Capture3d() {
    }

    public Capture3d(
            UUID organizationId,
            UUID developmentId,
            UUID unitId,
            UUID stageId,
            LocalDate captureDate,
            String description,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.unitId = unitId;
        this.stageId = stageId;
        this.captureDate = captureDate;
        this.description = description;
        this.status = CaptureStatus.PENDING;
        this.transform = SpatialTransform.identity();
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.storageKey = "developments/%s/captures/%s.spz".formatted(developmentId, this.id);
    }

    /** Corrige los datos descriptivos. La transformación espacial se edita aparte. */
    public void updateDetails(LocalDate captureDate, String description, String zone, UUID stageId) {
        this.captureDate = captureDate;
        this.description = description;
        this.zone = zone;
        this.stageId = stageId;
    }

    public void assignZone(String zone) {
        this.zone = zone;
    }

    public void markAvailable() {
        this.status = CaptureStatus.AVAILABLE;
        this.confirmedAt = Instant.now();
    }

    /** Alineación: reemplaza la transformación completa. */
    public void updateTransform(SpatialTransform newTransform) {
        this.transform = newTransform;
    }

    /** Marca esta captura como originada en un video y fija su clave de almacenamiento. */
    public void attachSourceVideo(String extension) {
        this.videoStorageKey =
                "developments/%s/videos/%s.%s".formatted(developmentId, id, extension);
    }

    /**
     * Fija la clave del video normalizado. Se asigna al tomar el trabajo, antes de que
     * el archivo exista, igual que {@code storageKey} se asigna al crear la captura: la
     * generación de claves vive en un solo lugar y el worker no la reconstruye por su
     * cuenta. La salida del preprocesamiento es siempre MP4.
     */
    public void markVideoNormalized() {
        this.normalizedVideoStorageKey =
                "developments/%s/videos/%s-normalized.mp4".formatted(developmentId, id);
    }

    /**
     * Fija la clave del resultado crudo del servicio externo. Es un archivo comprimido:
     * el PLY vive adentro y se extrae en la etapa siguiente del pipeline.
     */
    public void markResultKey() {
        this.resultStorageKey =
                "developments/%s/captures/%s-result.zip".formatted(developmentId, id);
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

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getStageId() {
        return stageId;
    }

    public LocalDate getCaptureDate() {
        return captureDate;
    }

    public String getZone() {
        return zone;
    }

    public String getDescription() {
        return description;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getBackupStorageKey() {
        return backupStorageKey;
    }

    public String getVideoStorageKey() {
        return videoStorageKey;
    }

    public String getNormalizedVideoStorageKey() {
        return normalizedVideoStorageKey;
    }

    public String getResultStorageKey() {
        return resultStorageKey;
    }

    public CaptureStatus getStatus() {
        return status;
    }

    public SpatialTransform getTransform() {
        return transform;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}