package com.obraviva.platform.developments;

import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDate;
import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "developments")
public class Development {

    @Id
    private UUID id;

    /** Referencia por id: organizations es otro módulo. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DevelopmentKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // --- Datos del desarrollo: todo opcional, nulo significa "sin cargar" ---

    @Column
    private String description;

    @Column
    private String locality;

    @Column
    private String province;

    @Enumerated(EnumType.STRING)
    @Column(name = "development_type")
    private DevelopmentType developmentType;

    @Column(name = "land_area_m2")
    private BigDecimal landAreaM2;

    @Column(name = "started_on")
    private LocalDate startedOn;

    /** Primer día del mes prometido, igual que la entrega de una casa. */
    @Column(name = "estimated_delivery")
    private LocalDate estimatedDelivery;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> amenities = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> services = new ArrayList<>();

    @Column
    private String architect;

    @Column
    private String builder;

    @Column(name = "permit_number")
    private String permitNumber;

    @Column(name = "cover_storage_key")
    private String coverStorageKey;

    @Column(name = "cover_pending_storage_key")
    private String coverPendingStorageKey;

    protected Development() {
    }

    public Development(UUID organizationId, String name, String address, DevelopmentKind kind) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name;
        this.address = address;
        this.kind = kind;
        this.createdAt = Instant.now();
    }

    /** Datos generales. Reemplaza todos: lo que no viene queda vacío, salvo el nombre. */
    public void replaceDetails(DevelopmentDetails details) {
        if (details.name() == null || details.name().isBlank()) {
            throw new IllegalArgumentException("El desarrollo tiene que tener nombre");
        }
        this.name = details.name().trim();
        this.address = blankToNull(details.address());
        this.description = blankToNull(details.description());
        this.locality = blankToNull(details.locality());
        this.province = blankToNull(details.province());
        this.developmentType = details.developmentType();
        this.landAreaM2 = details.landAreaM2();
        this.startedOn = details.startedOn();
        this.estimatedDelivery = details.estimatedDelivery();
        this.amenities = new ArrayList<>(details.amenities());
        this.services = new ArrayList<>(details.services());
        this.architect = blankToNull(details.architect());
        this.builder = blankToNull(details.builder());
        this.permitNumber = blankToNull(details.permitNumber());
    }

    /** Datos generales, ya validados por quien llama. */
    public record DevelopmentDetails(
            String name, String address, String description, String locality, String province,
            DevelopmentType developmentType, BigDecimal landAreaM2, LocalDate startedOn, LocalDate estimatedDelivery,
            List<String> amenities, List<String> services, String architect, String builder, String permitNumber) {
    }

    public String prepareCoverUpload(String extension) {
        this.coverPendingStorageKey = "developments/%s/cover/%s.%s".formatted(id, UUID.randomUUID(), extension);
        return coverPendingStorageKey;
    }

    /** Promueve la subida pendiente a portada. Devuelve la portada anterior, si había. */
    public String confirmCover() {
        String previous = coverStorageKey;
        this.coverStorageKey = coverPendingStorageKey;
        this.coverPendingStorageKey = null;
        return previous;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getDescription() {
        return description;
    }

    public String getLocality() {
        return locality;
    }

    public String getProvince() {
        return province;
    }

    public DevelopmentType getDevelopmentType() {
        return developmentType;
    }

    public BigDecimal getLandAreaM2() {
        return landAreaM2;
    }

    public LocalDate getStartedOn() {
        return startedOn;
    }

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public List<String> getAmenities() {
        return List.copyOf(amenities);
    }

    public List<String> getServices() {
        return List.copyOf(services);
    }

    public String getArchitect() {
        return architect;
    }

    public String getBuilder() {
        return builder;
    }

    public String getPermitNumber() {
        return permitNumber;
    }

    public String getCoverStorageKey() {
        return coverStorageKey;
    }

    public String getCoverPendingStorageKey() {
        return coverPendingStorageKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public DevelopmentKind getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}