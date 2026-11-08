package com.obraviva.platform.sharing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Enlace público que da acceso de sólo lectura a una unidad, sin sesión.
 *
 * <p>La entidad guarda el hash del token y nunca el token. Quien crea el enlace lo ve
 * una vez; después, ni la base ni la aplicación pueden reconstruirlo.
 */
@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column
    private String label;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Column(name = "access_count", nullable = false)
    private int accessCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShareLink() {
    }

    public ShareLink(
            UUID organizationId,
            UUID developmentId,
            UUID unitId,
            String tokenHash,
            String label,
            Instant expiresAt,
            UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.unitId = unitId;
        this.tokenHash = tokenHash;
        this.label = label;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.accessCount = 0;
        this.createdAt = Instant.now();
    }

    /**
     * Un enlace sirve mientras no lo hayan revocado y no se haya vencido.
     *
     * <p>Las dos condiciones se guardan por separado a propósito: el vencimiento es
     * automático y la revocación es un acto deliberado con autor y fecha. Colapsarlas
     * en un booleano perdería justo el dato que se busca cuando algo se filtró.
     */
    public boolean isUsableAt(Instant moment) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(moment);
    }

    public void revoke(UUID revokedBy) {
        if (this.revokedAt != null) {
            // Revocar dos veces no es un error: el acceso ya está cortado, que es lo
            // que el usuario quería. Se conserva la primera revocación, que es la que
            // efectivamente cortó el acceso.
            return;
        }
        this.revokedAt = Instant.now();
        this.revokedBy = revokedBy;
    }

    /** Registra una visita. Sirve para que el emisor sepa si el enlace circuló. */
    public void registerAccess() {
        this.lastAccessedAt = Instant.now();
        this.accessCount++;
    }

    public boolean isRevoked() {
        return revokedAt != null;
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

    public String getTokenHash() {
        return tokenHash;
    }

    public String getLabel() {
        return label;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}