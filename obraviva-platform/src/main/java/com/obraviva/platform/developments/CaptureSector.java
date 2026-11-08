package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Sector de una casa que se graba en 3D en cada fecha de recorrido. */
@Entity
@Table(name = "unit_capture_sectors")
public class CaptureSector {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "is_front", nullable = false)
    private boolean front;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CaptureSector() {
    }

    public CaptureSector(UUID organizationId, UUID unitId, String name, int orderIndex) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.unitId = unitId;
        this.name = name;
        this.orderIndex = orderIndex;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
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

    /** Un renombre conserva el sector: su id, y en cascada sus fotos y recorridos. */
    void rename(String name) {
        this.name = name;
    }

    void moveTo(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    /** El frente de la casa: a lo sumo uno, lo garantiza un índice único parcial. */
    void markFront(boolean front) {
        this.front = front;
    }

    public boolean isFront() {
        return front;
    }
}
