package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaVariantsStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Las versiones livianas de una imagen que no es un medio de obra (portadas y renders), con la
 * clave del original como identidad. Cada subida usa una clave nueva: reemplazar la imagen es
 * descartar esta fila y crear otra. Mismos estados que {@link MediaVariantsStatus}.
 */
@Entity
@Table(name = "image_variants")
class ImageVariant {

    @Id
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "thumb_storage_key")
    private String thumbStorageKey;

    @Column(name = "screen_storage_key")
    private String screenStorageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaVariantsStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImageVariant() {
    }

    /** Un SVG no se achica: es vectorial y liviano. Lo demás entra a la cola. */
    ImageVariant(UUID organizationId, String storageKey) {
        this.storageKey = storageKey;
        this.organizationId = organizationId;
        this.status = storageKey.toLowerCase(Locale.ROOT).endsWith(".svg")
                ? MediaVariantsStatus.SKIPPED : MediaVariantsStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** La clave de una versión, junto al original: "…/cover-x.png" da "…/cover-x-thumb.jpg". */
    static String variantKey(String storageKey, String suffix) {
        int dot = storageKey.lastIndexOf('.');
        String base = dot > storageKey.lastIndexOf('/') ? storageKey.substring(0, dot) : storageKey;
        return base + "-" + suffix + ".jpg";
    }

    /** Cuenta el intento antes de hacerlo: si el proceso se corta, el intento ya quedó contado. */
    void startAttempt() {
        this.attempts++;
    }

    void ready(String thumbStorageKey, String screenStorageKey) {
        this.thumbStorageKey = thumbStorageKey;
        this.screenStorageKey = screenStorageKey;
        this.status = MediaVariantsStatus.READY;
    }

    /** Un intento falló. Devuelve si se agotaron: entonces queda FAILED y se sigue viendo el original. */
    boolean attemptFailed(int maxAttempts) {
        if (attempts >= maxAttempts) {
            this.status = MediaVariantsStatus.FAILED;
            return true;
        }
        return false;
    }

    String getStorageKey() {
        return storageKey;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    String getThumbStorageKey() {
        return thumbStorageKey;
    }

    String getScreenStorageKey() {
        return screenStorageKey;
    }

    MediaVariantsStatus getStatus() {
        return status;
    }

    int getAttempts() {
        return attempts;
    }
}
