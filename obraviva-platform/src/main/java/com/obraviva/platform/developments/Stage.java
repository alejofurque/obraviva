package com.obraviva.platform.developments;

import java.time.LocalDate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stages")
public class Stage {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "development_id")
    private Development development;

    @Column(nullable = false)
    private String name;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status;

    /**
     * Avance físico declarado por el responsable, de 0 a 100.
     *
     * <p>No se deriva del estado ni lo modifica: son dos declaraciones distintas. El
     * estado responde "¿cómo va?" —y DELAYED es ortogonal al porcentaje, porque una
     * etapa puede estar al 60% y demorada— mientras que el porcentaje responde
     * "¿cuánto falta?". Encadenarlas convertiría una corrección de carga en un cambio
     * de estado no pedido.
     *
     * <p>Entero: nadie declara que una etapa está al 37,4%.
     */
    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    /**
     * Peso relativo de la etapa sobre el total de la obra. Excavar no pesa lo mismo
     * que levantar diez pisos.
     *
     * <p>Los pesos no están obligados a sumar 100: el avance global divide por la suma
     * real. Exigirlo obligaría a recalcular todas las etapas al agregar una.
     */
    @Column(nullable = false)
    private int weight;

    /** Presupuesto de la etapa. Nulo significa "sin presupuestar", distinto de cero. */
    @Column(name = "budget_amount")
    private BigDecimal budgetAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Casa a la que pertenece. Nula: obra común del desarrollo (calles, redes, portería). */
    @Column(name = "unit_id")
    private UUID unitId;

    /** Qué incluye la etapa, para quien la carga y para el comprador. */
    @Column
    private String description;

    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

    /** Se fija sola la primera vez que la etapa pasa a en ejecución. */
    @Column(name = "started_on")
    private LocalDate startedOn;

    /** Se fija sola al completarla, y se borra si se reabre. */
    @Column(name = "completed_on")
    private LocalDate completedOn;

    protected Stage() {
    }

    public Stage(Development development, String name, int orderIndex) {
        this.id = UUID.randomUUID();
        this.development = development;
        this.organizationId = development.getOrganizationId();
        this.name = name;
        this.orderIndex = orderIndex;
        this.status = StageStatus.PENDING;
        this.progressPercent = 0;
        this.weight = 1;
        this.createdAt = Instant.now();
    }

    /**
     * Completar una etapa lleva su avance a 100: "completada al 40%" no es un estado que
     * tenga sentido mostrarle a nadie. Al revés no se infiere nada: llegar a 100% no la
     * completa sola, porque puede faltar una inspección o una entrega.
     */
    public void changeStatus(StageStatus newStatus) {
        changeStatus(newStatus, LocalDate.now());
    }

    /**
     * La fecha de inicio real queda la primera vez que la etapa arranca; la de fin, al
     * completarla. Reabrir una etapa completada borra su fin: dejó de estar terminada.
     */
    public void changeStatus(StageStatus newStatus, LocalDate today) {
        this.status = newStatus;
        if (newStatus != StageStatus.PENDING && startedOn == null) {
            this.startedOn = today;
        }
        if (newStatus == StageStatus.COMPLETED) {
            this.progressPercent = 100;
            if (completedOn == null) {
                this.completedOn = today;
            }
        } else {
            this.completedOn = null;
        }
    }

    /** Etapa de una casa. Se asigna al crearla y no cambia. */
    public void assignUnit(UUID unitId) {
        this.unitId = unitId;
    }

    public void describe(String description) {
        this.description = description == null || description.isBlank() ? null : description.trim();
    }

    /** Fechas previstas. El fin no puede quedar antes del inicio. */
    public void plan(LocalDate plannedStart, LocalDate plannedEnd) {
        if (plannedStart != null && plannedEnd != null && plannedEnd.isBefore(plannedStart)) {
            throw new IllegalArgumentException("El fin previsto no puede ser anterior al inicio previsto");
        }
        this.plannedStart = plannedStart;
        this.plannedEnd = plannedEnd;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("La etapa tiene que tener nombre");
        }
        this.name = name.trim();
    }

    /** Declaración de avance. No toca el estado: ver el comentario del campo. */
    public void declareProgress(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException(
                    "El avance declarado debe estar entre 0 y 100: " + percent);
        }
        this.progressPercent = percent;
    }

    public void changeWeight(int weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("El peso de una etapa debe ser mayor que cero");
        }
        this.weight = weight;
    }

    /** Null borra el presupuesto: vuelve a "sin presupuestar". */
    public void changeBudget(BigDecimal budgetAmount) {
        if (budgetAmount != null && budgetAmount.signum() < 0) {
            throw new IllegalArgumentException("El presupuesto no puede ser negativo");
        }
        this.budgetAmount = budgetAmount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Development getDevelopment() {
        return development;
    }

    public String getName() {
        return name;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public StageStatus getStatus() {
        return status;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public int getWeight() {
        return weight;
    }

    public BigDecimal getBudgetAmount() {
        return budgetAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getPlannedStart() {
        return plannedStart;
    }

    public LocalDate getPlannedEnd() {
        return plannedEnd;
    }

    public LocalDate getStartedOn() {
        return startedOn;
    }

    public LocalDate getCompletedOn() {
        return completedOn;
    }
}