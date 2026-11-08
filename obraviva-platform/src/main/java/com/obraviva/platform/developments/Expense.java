package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Gasto declarado de una obra.
 *
 * <p>Monto nominal, sin indexar: comparar contra un presupuesto viejo subestima el
 * desvío con inflación alta. El panel lo muestra nominal y la limitación está
 * documentada; la indexación por series oficiales es evolución posterior a la tesis.
 *
 * <p>La etapa es opcional: hay gastos generales de obra que no corresponden a
 * ninguna. El total los incluye a todos; el semáforo por etapa, sólo los que tienen
 * etapa.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "development_id", nullable = false)
    private UUID developmentId;

    @Column(name = "stage_id")
    private UUID stageId;

    /** Casa del gasto. Nulo: gasto general o de obras comunes del desarrollo. */
    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "incurred_on", nullable = false)
    private LocalDate incurredOn;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column
    private String description;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Expense() {
    }

    public Expense(
            UUID organizationId,
            UUID developmentId,
            UUID stageId,
            LocalDate incurredOn,
            BigDecimal amount,
            String description,
            UUID createdBy) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El monto del gasto debe ser mayor que cero");
        }
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.developmentId = developmentId;
        this.stageId = stageId;
        this.incurredOn = incurredOn;
        this.amount = amount;
        this.description = description;
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

    /** Corrige los datos cargados. La casa y la obra no cambian. */
    public void update(UUID stageId, LocalDate incurredOn, BigDecimal amount, String description) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El monto del gasto debe ser mayor que cero");
        }
        this.stageId = stageId;
        this.incurredOn = incurredOn;
        this.amount = amount;
        this.description = description;
    }

    public void assignUnit(UUID unitId) {
        this.unitId = unitId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getStageId() {
        return stageId;
    }

    public LocalDate getIncurredOn() {
        return incurredOn;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}