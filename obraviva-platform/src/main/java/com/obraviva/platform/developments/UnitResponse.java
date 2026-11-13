package com.obraviva.platform.developments;

import com.obraviva.platform.media.variants.ImageVariants.SignedImage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Casa vista por su organización: ficha de venta, estado comercial y avance de su obra.
 * La portada se informa como presente o ausente: su URL firmada la arma la vista de la
 * casa, que es donde se muestra.
 *
 * <p>Para el encargado llegan nulos {@code buyerCount}, el precio y el estado comercial: no ve datos de
 * compradores ni información comercial. El avance es nulo en las respuestas de edición de
 * la ficha, que no lo recalculan.
 */
public record UnitResponse(
        UUID id,
        UUID developmentId,
        String code,
        String description,
        BigDecimal areaM2,
        Integer bedrooms,
        Integer bathrooms,
        List<String> features,
        YearMonth estimatedDelivery,
        boolean hasCover,
        UnitSalesStatus salesStatus,
        /** El día de la reserva o la venta; nulo si no hay o para el encargado. */
        LocalDate salesStatusOn,
        Integer buyerCount,
        String modelName,
        BigDecimal lotAreaM2,
        BigDecimal coveredAreaM2,
        BigDecimal semiCoveredAreaM2,
        Integer rooms,
        Integer toilettes,
        Integer floors,
        Integer parkingSpaces,
        String orientation,
        String longDescription,
        String finishes,
        BigDecimal listPrice,
        String priceCurrency,
        Integer progressPercent,
        String currentStageName,
        Integer stageCount,
        Instant createdAt,
        /**
         * Portada firmada, sólo en el listado de casas: la grilla la muestra. Miniatura y
         * pantalla nulas mientras no estén: se usa el original.
         */
        String coverUrl,
        String coverThumbUrl,
        String coverScreenUrl) {

    /** Avance de la obra de la casa. */
    public record UnitProgress(int percent, String currentStageName, int stageCount) {
    }

    /** Para el dueño, sin avance: las respuestas de edición de la ficha. */
    static UnitResponse from(Unit unit) {
        return from(unit, null, null, true);
    }

    static UnitResponse from(Unit unit, Integer buyerCount, UnitProgress progress, boolean showCommercial) {
        return new UnitResponse(
                unit.getId(),
                unit.getDevelopment().getId(),
                unit.getCode(),
                unit.getDescription(),
                unit.getAreaM2(),
                unit.getBedrooms(),
                unit.getBathrooms(),
                unit.getFeatures(),
                unit.getEstimatedDelivery(),
                unit.getCoverStorageKey() != null,
                showCommercial ? unit.getSalesStatus() : null,
                showCommercial ? unit.getSalesStatusOn() : null,
                buyerCount,
                unit.getModelName(),
                unit.getLotAreaM2(),
                unit.getCoveredAreaM2(),
                unit.getSemiCoveredAreaM2(),
                unit.getRooms(),
                unit.getToilettes(),
                unit.getFloors(),
                unit.getParkingSpaces(),
                unit.getOrientation(),
                unit.getLongDescription(),
                unit.getFinishes(),
                showCommercial ? unit.getListPrice() : null,
                showCommercial ? unit.getPriceCurrency() : null,
                progress == null ? null : progress.percent(),
                progress == null ? null : progress.currentStageName(),
                progress == null ? null : progress.stageCount(),
                unit.getCreatedAt(),
                null,
                null,
                null);
    }

    UnitResponse withCover(SignedImage cover) {
        return new UnitResponse(id, developmentId, code, description, areaM2, bedrooms, bathrooms, features,
                estimatedDelivery, hasCover, salesStatus, salesStatusOn, buyerCount, modelName, lotAreaM2, coveredAreaM2,
                semiCoveredAreaM2, rooms, toilettes, floors, parkingSpaces, orientation, longDescription, finishes,
                listPrice, priceCurrency, progressPercent, currentStageName, stageCount, createdAt,
                cover == null ? null : cover.url(), cover == null ? null : cover.thumbUrl(), cover == null ? null : cover.screenUrl());
    }
}
