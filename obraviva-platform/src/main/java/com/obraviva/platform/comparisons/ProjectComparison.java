package com.obraviva.platform.comparisons;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un lugar de la obra visto dos veces: como se proyectó (el render) y como está hoy (una
 * foto de avance). Se muestra al comprador sólo cuando tiene las dos.
 */
@Entity
@Table(name = "project_comparisons")
public class ProjectComparison {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    /** Nulo: comparación de la obra, visible para todos sus compradores. */
    @Column(name = "unit_id")
    private UUID unitId;

    @Column(nullable = false)
    private String title;

    @Column(name = "render_storage_key")
    private String renderStorageKey;

    @Column(name = "render_pending_storage_key")
    private String renderPendingStorageKey;

    @Column(name = "photo_media_id")
    private UUID photoMediaId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectComparison() {
    }

    public ProjectComparison(UUID organizationId, UUID developmentId, UUID unitId, String title, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.unitId = unitId;
        this.title = title;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void update(String title, UUID unitId, UUID photoMediaId) {
        this.title = title;
        this.unitId = unitId;
        this.photoMediaId = photoMediaId;
    }

    /** Cada subida usa una clave nueva: el render vigente no se pisa hasta confirmar. */
    public String prepareRenderUpload(String extension) {
        this.renderPendingStorageKey = "developments/%s/comparisons/%s/%s.%s"
                .formatted(developmentId, id, UUID.randomUUID(), extension);
        return renderPendingStorageKey;
    }

    /** Promueve la subida pendiente a render. Devuelve el anterior, si había. */
    public String confirmRender() {
        String previous = renderStorageKey;
        this.renderStorageKey = renderPendingStorageKey;
        this.renderPendingStorageKey = null;
        return previous;
    }

    public boolean isComplete() {
        return renderStorageKey != null && photoMediaId != null;
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

    public String getTitle() {
        return title;
    }

    public String getRenderStorageKey() {
        return renderStorageKey;
    }

    public String getRenderPendingStorageKey() {
        return renderPendingStorageKey;
    }

    public UUID getPhotoMediaId() {
        return photoMediaId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
