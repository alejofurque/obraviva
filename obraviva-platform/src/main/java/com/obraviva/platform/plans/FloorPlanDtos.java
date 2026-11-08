package com.obraviva.platform.plans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class FloorPlanDtos {

    private FloorPlanDtos() {
    }

    /** Dimensiones en metros, entre medio metro y quinientos: una unidad, no un barrio. */
    public record CreateFloorPlanRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.5") @DecimalMax("500") @Digits(integer = 3, fraction = 2) BigDecimal widthM,
            @NotNull @DecimalMin("0.5") @DecimalMax("500") @Digits(integer = 3, fraction = 2) BigDecimal depthM) {
    }

    public record UpdateFloorPlanRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.5") @DecimalMax("500") @Digits(integer = 3, fraction = 2) BigDecimal widthM,
            @NotNull @DecimalMin("0.5") @DecimalMax("500") @Digits(integer = 3, fraction = 2) BigDecimal depthM,
            @NotNull @Min(0) Integer orderIndex) {
    }

    /**
     * Polígono como x1, y1, x2, y2... en metros: entre 3 y 100 vértices. La paridad y
     * los límites de la planta se verifican en el servicio.
     */
    public record RoomRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Size(min = 6, max = 200) List<Double> polygon,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal declaredAreaM2) {
    }

    public record ReplaceRoomsRequest(@NotNull @Size(max = 40) List<@Valid @NotNull RoomRequest> rooms) {
    }

    public record RoomResponse(
            UUID id,
            String name,
            List<Double> polygon,
            BigDecimal areaM2,
            boolean declaredArea) {
    }

    /**
     * {@code imageUrl} viene firmada; es nula si la planta no tiene imagen. Las dimensiones
     * en píxeles sirven para proponer la profundidad a partir del ancho real.
     */
    public record FloorPlanResponse(
            UUID id,
            UUID unitId,
            String name,
            int orderIndex,
            BigDecimal widthM,
            BigDecimal depthM,
            List<RoomResponse> rooms,
            String imageUrl,
            Integer imageWidthPx,
            Integer imageHeightPx) {
    }

    public record RequestPlanImageUploadRequest(
            @NotBlank @Size(max = 300) String filename,
            @NotBlank @Size(max = 100) String contentType) {
    }

    /** El navegador sube con PUT a {@code uploadUrl} con exactamente este Content-Type. */
    public record PlanImageUploadResponse(String uploadUrl, String contentType) {
    }
}
