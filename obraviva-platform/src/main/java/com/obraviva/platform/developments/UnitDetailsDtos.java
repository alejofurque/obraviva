package com.obraviva.platform.developments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class UnitDetailsDtos {

    private UnitDetailsDtos() {
    }

    /**
     * Ficha completa de la unidad. Todos los componentes son opcionales y ninguno es
     * primitivo: un cuerpo parcial es válido y lo ausente queda vacío.
     */
    public record UpdateUnitDetailsRequest(
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal areaM2,
            @Min(0) @Max(50) Integer bedrooms,
            @Min(0) @Max(50) Integer bathrooms,
            @Size(max = 12) List<@Size(max = 60) String> features,
            YearMonth estimatedDelivery) {
    }

    /** {@code on}: el día de la reserva o la venta; sin él, hoy. Disponible no lleva día. */
    public record UpdateSalesStatusRequest(@NotNull UnitSalesStatus status, LocalDate on) {
    }

    /** Casas en lote: "Casa 1" a "Casa 10" con prefijo "Casa", primer número 1 y cantidad 10. */
    public record CreateUnitsBatchRequest(
            @NotBlank @Size(max = 40) String prefix,
            @NotNull @Min(0) @Max(9999) Integer firstNumber,
            @NotNull @Min(1) @Max(200) Integer count,
            Boolean withStandardStages) {
    }

    public record UpdateUnitRequest(
            @NotBlank @Size(max = 50) String code,
            @Size(max = 300) String description) {
    }

    /**
     * Ficha de venta ampliada de la casa. Todo opcional y sin primitivos; lo ausente queda
     * vacío. El precio va con su moneda o no va.
     */
    public record UpdateSalesSheetRequest(
            @Size(max = 80) String modelName,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal lotAreaM2,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal coveredAreaM2,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal semiCoveredAreaM2,
            @Min(0) @Max(50) Integer rooms,
            @Min(0) @Max(50) Integer toilettes,
            @Min(1) @Max(10) Integer floors,
            @Min(0) @Max(20) Integer parkingSpaces,
            @Size(max = 20) String orientation,
            @Size(max = 4000) String longDescription,
            @Size(max = 4000) String finishes,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2) BigDecimal listPrice,
            @Pattern(regexp = "ARS|USD") String priceCurrency) {
    }

    public record RequestCoverUploadRequest(
            @NotBlank @Size(max = 300) String filename,
            @NotBlank @Size(max = 100) String contentType) {
    }

    /**
     * El cliente sube la imagen con PUT a {@code uploadUrl}, con exactamente este
     * Content-Type, que la firma incluye.
     */
    public record CoverUploadResponse(String uploadUrl, String contentType) {
    }
}
