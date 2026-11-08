package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Vínculo entre un usuario y la unidad que compró.
 *
 * <p>Referencia la unidad por identificador y no con una relación JPA: no hace falta
 * navegar desde acá hacia la unidad, y el identificador alcanza para todas las
 * consultas que este vínculo sirve.
 */
@Entity
@Table(name = "unit_members")
public class UnitMember {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UnitMember() {
    }

    public UnitMember(UUID organizationId, UUID unitId, UUID userId, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
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

    public UUID getUnitId() {
        return unitId;
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