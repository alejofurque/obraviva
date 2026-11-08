package com.obraviva.platform.developments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "units")
public class Unit {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Relación JPA permitida: Development es del mismo módulo. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "development_id")
    private Development development;

    @Column(nullable = false)
    private String code;

    @Column
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // --- Ficha: todo opcional, nulo significa "sin cargar" ---

    @Column(name = "area_m2")
    private BigDecimal areaM2;

    @Column
    private Integer bedrooms;

    @Column
    private Integer bathrooms;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> features = new ArrayList<>();

    /** Primer día del mes prometido: la entrega se estima por mes, no por día. */
    @Column(name = "estimated_delivery")
    private LocalDate estimatedDelivery;

    @Column(name = "cover_storage_key")
    private String coverStorageKey;

    @Column(name = "cover_pending_storage_key")
    private String coverPendingStorageKey;

    // --- Render del frente (V31): cómo se proyectó, para la comparación del comprador ---

    @Column(name = "front_render_storage_key")
    private String frontRenderStorageKey;

    @Column(name = "front_render_pending_storage_key")
    private String frontRenderPendingStorageKey;

    // --- Ficha de venta ampliada (V27) ---

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "lot_area_m2")
    private BigDecimal lotAreaM2;

    @Column(name = "covered_area_m2")
    private BigDecimal coveredAreaM2;

    @Column(name = "semi_covered_area_m2")
    private BigDecimal semiCoveredAreaM2;

    @Column
    private Integer rooms;

    @Column
    private Integer toilettes;

    @Column
    private Integer floors;

    @Column(name = "parking_spaces")
    private Integer parkingSpaces;

    @Column
    private String orientation;

    @Column(name = "long_description")
    private String longDescription;

    @Column
    private String finishes;

    /** Precio de lista: interno, nunca sale hacia el comprador ni el enlace público. */
    @Column(name = "list_price")
    private BigDecimal listPrice;

    @Column(name = "price_currency")
    private String priceCurrency;

    /** Estado comercial: interno de la organización, no sale hacia el comprador. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sales_status", nullable = false)
    private UnitSalesStatus salesStatus = UnitSalesStatus.AVAILABLE;

    /** El día de la reserva o de la venta (V35). Nulo si está disponible o si no se cargó. */
    @Column(name = "sales_status_on")
    private java.time.LocalDate salesStatusOn;

    protected Unit() {
    }

    public Unit(Development development, String code, String description) {
        this.id = UUID.randomUUID();
        this.development = development;
        this.organizationId = development.getOrganizationId();
        this.code = code;
        this.description = description;
        this.createdAt = Instant.now();
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

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Reemplaza la ficha completa. Lo que no viene queda vacío: la ficha se edita como un
     * formulario entero, y así un campo borrado en pantalla se borra también acá.
     */
    /** Ficha de venta ampliada. Reemplaza todo: lo que no viene queda vacío. */
    public void replaceSalesSheet(SalesSheet sheet) {
        this.modelName = blankToNull(sheet.modelName());
        this.lotAreaM2 = sheet.lotAreaM2();
        this.coveredAreaM2 = sheet.coveredAreaM2();
        this.semiCoveredAreaM2 = sheet.semiCoveredAreaM2();
        this.rooms = sheet.rooms();
        this.toilettes = sheet.toilettes();
        this.floors = sheet.floors();
        this.parkingSpaces = sheet.parkingSpaces();
        this.orientation = blankToNull(sheet.orientation());
        this.longDescription = blankToNull(sheet.longDescription());
        this.finishes = blankToNull(sheet.finishes());
        if ((sheet.listPrice() == null) != (sheet.priceCurrency() == null)) {
            throw new IllegalArgumentException("El precio y su moneda van juntos");
        }
        this.listPrice = sheet.listPrice();
        this.priceCurrency = sheet.priceCurrency();
    }

    public record SalesSheet(
            String modelName, BigDecimal lotAreaM2, BigDecimal coveredAreaM2, BigDecimal semiCoveredAreaM2,
            Integer rooms, Integer toilettes, Integer floors, Integer parkingSpaces, String orientation,
            String longDescription, String finishes, BigDecimal listPrice, String priceCurrency) {
    }

    /** Nombre visible de la casa: su código, por ejemplo "Casa 12" o "Lote 4". */
    public void rename(String code, String description) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("La casa tiene que tener nombre");
        }
        this.code = code.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getModelName() {
        return modelName;
    }

    public BigDecimal getLotAreaM2() {
        return lotAreaM2;
    }

    public BigDecimal getCoveredAreaM2() {
        return coveredAreaM2;
    }

    public BigDecimal getSemiCoveredAreaM2() {
        return semiCoveredAreaM2;
    }

    public Integer getRooms() {
        return rooms;
    }

    public Integer getToilettes() {
        return toilettes;
    }

    public Integer getFloors() {
        return floors;
    }

    public Integer getParkingSpaces() {
        return parkingSpaces;
    }

    public String getOrientation() {
        return orientation;
    }

    public String getLongDescription() {
        return longDescription;
    }

    public String getFinishes() {
        return finishes;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    /** El estado y su día; disponible no tiene día. Quien llama ya validó que no sea futuro. */
    public void changeSalesStatus(UnitSalesStatus status, java.time.LocalDate on) {
        if (status == null) {
            throw new IllegalArgumentException("El estado comercial es obligatorio");
        }
        this.salesStatus = status;
        this.salesStatusOn = status == UnitSalesStatus.AVAILABLE ? null : on;
    }

    public java.time.LocalDate getSalesStatusOn() {
        return salesStatusOn;
    }

    /**
     * Vincular un comprador a una unidad disponible la da por vendida. Una reservada no
     * cambia sola: cerrar la reserva es una decisión del dueño.
     */
    public void markSoldIfAvailable() {
        if (salesStatus == UnitSalesStatus.AVAILABLE) {
            salesStatus = UnitSalesStatus.SOLD;
        }
    }

    public UnitSalesStatus getSalesStatus() {
        return salesStatus;
    }

    public void replaceDetails(
            BigDecimal areaM2,
            Integer bedrooms,
            Integer bathrooms,
            List<String> features,
            YearMonth estimatedDelivery) {
        this.areaM2 = areaM2;
        this.bedrooms = bedrooms;
        this.bathrooms = bathrooms;
        this.features = new ArrayList<>(features);
        this.estimatedDelivery = estimatedDelivery == null ? null : estimatedDelivery.atDay(1);
    }

    /** Emite la clave de una portada nueva; la vigente sigue hasta confirmar la subida. */
    public String prepareCoverUpload(String extension) {
        this.coverPendingStorageKey = "developments/%s/units/%s/cover-%s.%s"
                .formatted(development.getId(), id, UUID.randomUUID(), extension);
        return coverPendingStorageKey;
    }

    /** Promueve la subida pendiente a portada. Devuelve la portada anterior, si había. */
    public String confirmCover() {
        if (coverPendingStorageKey == null) {
            throw new IllegalStateException("No hay una subida de portada pendiente");
        }
        String previous = coverStorageKey;
        this.coverStorageKey = coverPendingStorageKey;
        this.coverPendingStorageKey = null;
        return previous;
    }

    /** Emite la clave de un render del frente nuevo; el vigente sigue hasta confirmar la subida. */
    public String prepareFrontRenderUpload(String extension) {
        this.frontRenderPendingStorageKey = "developments/%s/units/%s/front-render-%s.%s"
                .formatted(development.getId(), id, UUID.randomUUID(), extension);
        return frontRenderPendingStorageKey;
    }

    /** Promueve la subida pendiente a render del frente. Devuelve el anterior, si había. */
    public String confirmFrontRender() {
        if (frontRenderPendingStorageKey == null) {
            throw new IllegalStateException("No hay una subida de render pendiente");
        }
        String previous = frontRenderStorageKey;
        this.frontRenderStorageKey = frontRenderPendingStorageKey;
        this.frontRenderPendingStorageKey = null;
        return previous;
    }

    public String getFrontRenderStorageKey() {
        return frontRenderStorageKey;
    }

    public String getFrontRenderPendingStorageKey() {
        return frontRenderPendingStorageKey;
    }

    public BigDecimal getAreaM2() {
        return areaM2;
    }

    public Integer getBedrooms() {
        return bedrooms;
    }

    public Integer getBathrooms() {
        return bathrooms;
    }

    public List<String> getFeatures() {
        return List.copyOf(features);
    }

    public YearMonth getEstimatedDelivery() {
        return estimatedDelivery == null ? null : YearMonth.from(estimatedDelivery);
    }

    public String getCoverStorageKey() {
        return coverStorageKey;
    }

    public String getCoverPendingStorageKey() {
        return coverPendingStorageKey;
    }
}