package com.obraviva.platform.organizations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_members")
public class OrganizationMember {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /**
     * Referencia por id, sin relación JPA: User pertenece al módulo
     * auth y las fronteras modulares no se cruzan con entidades.
     * La integridad referencial la garantiza la FK en la base.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizationRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrganizationMember() {
        // Requerido por JPA
    }

    public OrganizationMember(Organization organization, UUID userId, OrganizationRole role) {
        this.id = UUID.randomUUID();
        this.organization = organization;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}