package com.obraviva.platform.plans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Ambiente de una planta: un polígono en metros, con nombre y superficie. */
@Entity
@Table(name = "plan_rooms")
public class PlanRoom {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_plan_id")
    private FloorPlan floorPlan;

    @Column(nullable = false)
    private String name;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    /** Vértices en metros, como x1, y1, x2, y2... en el orden del contorno. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "double precision[]")
    private double[] polygon;

    @Column(name = "declared_area_m2")
    private BigDecimal declaredAreaM2;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlanRoom() {
    }

    PlanRoom(FloorPlan floorPlan, String name, int orderIndex, double[] polygon, BigDecimal declaredAreaM2) {
        this.id = UUID.randomUUID();
        this.organizationId = floorPlan.getOrganizationId();
        this.floorPlan = floorPlan;
        this.name = name;
        this.orderIndex = orderIndex;
        this.polygon = polygon.clone();
        this.declaredAreaM2 = declaredAreaM2;
        this.createdAt = Instant.now();
    }

    /**
     * Superficie a mostrar: la declarada si el dueño la cargó, y si no la del dibujo. La
     * declarada es la del plano de arquitectura y manda sobre un dibujo aproximado.
     */
    public BigDecimal areaM2() {
        return declaredAreaM2 != null ? declaredAreaM2 : drawnAreaM2(polygon);
    }

    /** Fórmula del área de Gauss sobre el contorno, en metros cuadrados con dos decimales. */
    static BigDecimal drawnAreaM2(double[] polygon) {
        int points = polygon.length / 2;
        double doubled = 0;
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            doubled += polygon[2 * i] * polygon[2 * next + 1] - polygon[2 * next] * polygon[2 * i + 1];
        }
        return BigDecimal.valueOf(Math.abs(doubled) / 2).setScale(2, RoundingMode.HALF_UP);
    }

    /** Si todos los vértices caen dentro de una planta de las dimensiones dadas. */
    boolean fitsWithin(BigDecimal widthM, BigDecimal depthM) {
        return PlanGeometry.fitsWithin(polygon, widthM.doubleValue(), depthM.doubleValue());
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public double[] getPolygon() {
        return polygon.clone();
    }

    public boolean hasDeclaredArea() {
        return declaredAreaM2 != null;
    }
}
