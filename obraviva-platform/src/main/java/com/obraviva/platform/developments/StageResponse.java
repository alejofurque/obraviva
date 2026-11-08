package com.obraviva.platform.developments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Etapa de una casa o, con {@code unitId} nulo, de las obras comunes del desarrollo.
 *
 * <p>{@code startedOn} y {@code completedOn} los fija el sistema al cambiar el estado;
 * las fechas previstas las carga el dueño.
 */
public record StageResponse(
        UUID id,
        UUID developmentId,
        UUID unitId,
        String name,
        String description,
        int orderIndex,
        StageStatus status,
        int progressPercent,
        int weight,
        BigDecimal budgetAmount,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        LocalDate startedOn,
        LocalDate completedOn,
        Instant createdAt) {

    /**
     * Respuesta según quién mira: el presupuesto es información económica y el
     * encargado no la ve. Se omite el valor, no el campo, para que la forma de la
     * respuesta no dependa del rol.
     */
    static StageResponse forViewer(Stage stage, boolean owner) {
        return build(stage, owner ? stage.getBudgetAmount() : null);
    }

    static StageResponse from(Stage stage) {
        return build(stage, stage.getBudgetAmount());
    }

    private static StageResponse build(Stage stage, BigDecimal budget) {
        return new StageResponse(
                stage.getId(),
                stage.getDevelopment().getId(),
                stage.getUnitId(),
                stage.getName(),
                stage.getDescription(),
                stage.getOrderIndex(),
                stage.getStatus(),
                stage.getProgressPercent(),
                stage.getWeight(),
                budget,
                stage.getPlannedStart(),
                stage.getPlannedEnd(),
                stage.getStartedOn(),
                stage.getCompletedOn(),
                stage.getCreatedAt());
    }
}
