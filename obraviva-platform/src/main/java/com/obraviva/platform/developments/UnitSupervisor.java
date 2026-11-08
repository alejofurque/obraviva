package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Encargado asignado a una casa puntual. Llega a su desarrollo, pero carga sólo lo de
 * sus casas. Un encargado de todo el desarrollo está en {@link DevelopmentMember}.
 */
@Entity
@Table(name = "unit_supervisors")
public class UnitSupervisor {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UnitSupervisor() {
    }

    public UnitSupervisor(UUID organizationId, UUID developmentId, UUID unitId, UUID userId, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.unitId = unitId;
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

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
