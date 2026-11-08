package com.obraviva.platform.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "progress_entry_id")
    private UUID progressEntryId;

    /** Casa de la foto: por su avance, o por la galería de la casa. Nula: del desarrollo. */
    @Column(name = "unit_id")
    private UUID unitId;

    /** Lugar de la casa (un sector del recorrido), por nombre. Nulo: "General". */
    @Column(name = "sector")
    private String sector;

    /** Galería de la casa: lo que se muestra para vender. No es evidencia de obra. */
    @Column(name = "showcase", nullable = false)
    private boolean showcase;

    /** Día en que se sacó: el de la sesión de recorrido. Nulo: el de su avance o el de la subida. */
    @Column(name = "taken_on")
    private java.time.LocalDate takenOn;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    // --- Versiones livianas (V34): miniatura y pantalla en JPEG; el original se conserva ---

    @Column(name = "thumb_storage_key")
    private String thumbStorageKey;

    @Column(name = "screen_storage_key")
    private String screenStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "variants_status")
    private MediaVariantsStatus variantsStatus;

    @Column(name = "variants_attempts", nullable = false)
    private int variantsAttempts;

    protected MediaAsset() {
    }

    public MediaAsset(
            UUID organizationId,
            UUID developmentId,
            UUID progressEntryId,
            String originalFilename,
            String contentType,
            MediaKind kind,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.progressEntryId = progressEntryId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.kind = kind;
        this.status = MediaStatus.PENDING;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.storageKey = buildStorageKey(developmentId, this.id, originalFilename);
    }

    /** Clave del objeto: developments/{id}/media/{mediaId}.{extensión} */
    public void assignUnit(UUID unitId) {
        this.unitId = unitId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    /** Quien llama ya validó que el sector sea de la casa de la foto. */
    public void assignSector(String sector) {
        this.sector = sector;
    }

    public String getSector() {
        return sector;
    }

    public void classify(boolean showcase, java.time.LocalDate takenOn) {
        this.showcase = showcase;
        this.takenOn = takenOn;
    }

    public boolean isShowcase() {
        return showcase;
    }

    public java.time.LocalDate getTakenOn() {
        return takenOn;
    }

    private static String buildStorageKey(UUID developmentId, UUID mediaId, String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = (dot > 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase()
                : "bin";
        return "developments/%s/media/%s.%s".formatted(developmentId, mediaId, extension);
    }

    /**
     * Cuelga de un avance una foto subida antes que él. Sólo una foto sin avance: una que
     * ya tiene uno no se mueve a otro.
     */
    public void attachToEntry(UUID progressEntryId) {
        if (this.progressEntryId != null) {
            throw new IllegalStateException("La foto ya pertenece a un avance");
        }
        this.progressEntryId = progressEntryId;
        // Una foto de un avance es evidencia de obra, nunca de la galería.
        this.showcase = false;
    }

    /**
     * Disponible: entra a la cola de versiones livianas. Un SVG no se achica: es vectorial y
     * liviano, y ffmpeg no lo rasteriza.
     */
    public void markAvailable() {
        this.status = MediaStatus.AVAILABLE;
        this.confirmedAt = Instant.now();
        this.variantsStatus = "image/svg+xml".equals(contentType) ? MediaVariantsStatus.SKIPPED : MediaVariantsStatus.PENDING;
    }

    /** La clave de una versión: la del original sin extensión, con su sufijo, en JPEG. */
    public String variantKey(String suffix) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > storageKey.lastIndexOf('/') ? storageKey.substring(0, dot) : storageKey;
        return base + "-" + suffix + ".jpg";
    }

    /** Cuenta el intento antes de hacerlo: si el proceso se corta, el intento ya quedó contado. */
    public void startVariantsAttempt() {
        this.variantsAttempts++;
    }

    public void variantsReady(String thumbStorageKey, String screenStorageKey) {
        this.thumbStorageKey = thumbStorageKey;
        this.screenStorageKey = screenStorageKey;
        this.variantsStatus = MediaVariantsStatus.READY;
    }

    /** Un intento falló. Devuelve si se agotaron: entonces queda FAILED y se sigue viendo el original. */
    public boolean variantsAttemptFailed(int maxAttempts) {
        if (variantsAttempts >= maxAttempts) {
            this.variantsStatus = MediaVariantsStatus.FAILED;
            return true;
        }
        return false;
    }

    public String getThumbStorageKey() {
        return thumbStorageKey;
    }

    public String getScreenStorageKey() {
        return screenStorageKey;
    }

    public MediaVariantsStatus getVariantsStatus() {
        return variantsStatus;
    }

    public int getVariantsAttempts() {
        return variantsAttempts;
    }

    public boolean isAvailable() {
        return status == MediaStatus.AVAILABLE;
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

    public UUID getProgressEntryId() {
        return progressEntryId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public MediaKind getKind() {
        return kind;
    }

    public MediaStatus getStatus() {
        return status;
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