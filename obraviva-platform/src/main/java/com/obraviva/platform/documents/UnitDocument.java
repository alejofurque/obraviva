package com.obraviva.platform.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Documento autorizado: de una unidad, o de la obra entera si no tiene unidad. Queda
 * pendiente hasta que se confirma la subida, igual que las fotos.
 */
@Entity
@Table(name = "unit_documents")
public class UnitDocument {

    public enum Category { PLANO, CONTRATO, REGLAMENTO, MEMORIA, OTRO }

    public enum Status { PENDING, AVAILABLE }

    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,5}");

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UnitDocument() {
    }

    public UnitDocument(UUID organizationId, UUID developmentId, UUID unitId, String title, Category category,
                        String originalFilename, String contentType, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.unitId = unitId;
        this.title = title;
        this.category = category;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.storageKey = "developments/%s/documents/%s.%s".formatted(developmentId, id, extensionOf(originalFilename));
        this.status = Status.PENDING;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void markAvailable() {
        this.status = Status.AVAILABLE;
    }

    /** La extensión termina en una clave de almacenamiento: sólo se acepta algo inocuo. */
    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "bin";
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

    public Category getCategory() {
        return category;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
