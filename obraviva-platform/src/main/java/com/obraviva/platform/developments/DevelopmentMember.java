package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Encargado asignado a una obra. El dueño de la organización no necesita asignación: ve
 * todas sus obras. Un encargado sólo ve las que tiene acá.
 */
@Entity
@Table(name = "development_members")
public class DevelopmentMember {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Nulo en las asignaciones migradas desde el acceso por organización. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DevelopmentMember() {
    }

    public DevelopmentMember(UUID organizationId, UUID developmentId, UUID userId, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.userId = userId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
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

    public UUID getUserId() {
        return userId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
