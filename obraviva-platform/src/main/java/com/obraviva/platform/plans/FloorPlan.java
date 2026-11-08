package com.obraviva.platform.plans;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Planta de una unidad. Sus ambientes le pertenecen: se guardan, se reemplazan y se
 * borran con ella.
 */
@Entity
@Table(name = "floor_plans")
public class FloorPlan {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Referencia por identificador: la unidad es de otro módulo. */
    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(nullable = false)
    private String name;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "width_m", nullable = false)
    private BigDecimal widthM;

    @Column(name = "depth_m", nullable = false)
    private BigDecimal depthM;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // --- Imagen del plano: el archivo del estudio o una foto del plano impreso ---

    @Column(name = "image_storage_key")
    private String imageStorageKey;

    @Column(name = "image_pending_storage_key")
    private String imagePendingStorageKey;

    @Column(name = "image_width_px")
    private Integer imageWidthPx;

    @Column(name = "image_height_px")
    private Integer imageHeightPx;

    @OneToMany(mappedBy = "floorPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<PlanRoom> rooms = new ArrayList<>();

    protected FloorPlan() {
    }

    public FloorPlan(UUID organizationId, UUID unitId, String name, int orderIndex,
                     BigDecimal widthM, BigDecimal depthM) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.unitId = unitId;
        this.name = name;
        this.orderIndex = orderIndex;
        this.widthM = widthM;
        this.depthM = depthM;
        this.createdAt = Instant.now();
    }

    /** Nuevo nombre, dimensiones y orden. Falla si algún ambiente queda afuera. */
    public void update(String name, BigDecimal widthM, BigDecimal depthM, int orderIndex) {
        if (rooms.stream().anyMatch(room -> !room.fitsWithin(widthM, depthM))) {
            throw new IllegalArgumentException("Hay ambientes que quedarían fuera de la planta");
        }
        this.name = name;
        this.widthM = widthM;
        this.depthM = depthM;
        this.orderIndex = orderIndex;
    }

    /** Descripción de un ambiente a crear, ya validado por quien llama. */
    public record RoomSpec(String name, double[] polygon, BigDecimal declaredAreaM2) {
    }

    /**
     * Reemplaza todos los ambientes. El editor guarda la planta entera de una vez: así
     * no hay estados intermedios con la mitad de los cambios aplicados.
     */
    public void replaceRooms(List<RoomSpec> specs) {
        rooms.clear();
        for (int i = 0; i < specs.size(); i++) {
            RoomSpec spec = specs.get(i);
            if (!PlanGeometry.fitsWithin(spec.polygon(), widthM.doubleValue(), depthM.doubleValue())) {
                throw new IllegalArgumentException(
                        "El ambiente '" + spec.name() + "' tiene vértices fuera de la planta");
            }
            rooms.add(new PlanRoom(this, spec.name(), i, spec.polygon(), spec.declaredAreaM2()));
        }
    }

    /**
     * Reserva la clave donde el navegador sube el archivo. Cada subida usa una clave nueva:
     * la imagen vigente no se pisa hasta que la nueva se confirme.
     */
    public String prepareImageUpload(String extension) {
        this.imagePendingStorageKey = "units/%s/plans/%s/%s.%s".formatted(unitId, id, UUID.randomUUID(), extension);
        return imagePendingStorageKey;
    }

    public void confirmImage(String storageKey, int widthPx, int heightPx) {
        this.imageStorageKey = storageKey;
        this.imageWidthPx = widthPx;
        this.imageHeightPx = heightPx;
        this.imagePendingStorageKey = null;
    }

    public void discardPendingImage() {
        this.imagePendingStorageKey = null;
    }

    /** Sin imagen, la planta se dibuja sólo con sus ambientes, como antes. */
    public void removeImage() {
        this.imageStorageKey = null;
        this.imageWidthPx = null;
        this.imageHeightPx = null;
    }

    public String getImageStorageKey() {
        return imageStorageKey;
    }

    public String getImagePendingStorageKey() {
        return imagePendingStorageKey;
    }

    public Integer getImageWidthPx() {
        return imageWidthPx;
    }

    public Integer getImageHeightPx() {
        return imageHeightPx;
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

    public String getName() {
        return name;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public BigDecimal getWidthM() {
        return widthM;
    }

    public BigDecimal getDepthM() {
        return depthM;
    }

    public List<PlanRoom> getRooms() {
        return List.copyOf(rooms);
    }
}
