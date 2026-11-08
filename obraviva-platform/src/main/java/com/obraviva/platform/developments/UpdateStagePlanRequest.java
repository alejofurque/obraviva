package com.obraviva.platform.developments;

import java.time.LocalDate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Declaración de avance, nombre, peso y presupuesto de una etapa.
 *
 * <p>Los cuatro campos son opcionales y sólo se aplica lo que venga: declarar el
 * avance semanal no debería obligar a repetir el presupuesto. La contra es que no se
 * puede borrar un presupuesto mandando null, porque null significa "no lo toques";
 * para eso está {@code clearBudget}.
 *
 * <p><strong>{@code clearBudget} es Boolean y no boolean.</strong> Jackson 3 rechaza
 * la construcción de un record cuando falta un componente primitivo en el JSON, en vez
 * de asumir false, así que un cuerpo que no lo mencione devolvería 400. Los tests no
 * detectan esto porque construyen el record en Java con todos sus componentes y nunca
 * pasan por la deserialización.
 */
public record UpdateStagePlanRequest(
        @Min(0) @Max(100) Integer progressPercent,
        @Size(max = 200) String name,
        @Min(1) Integer weight,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal budgetAmount,
        Boolean clearBudget,
        @Size(max = 2000) String description,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        Boolean clearPlannedDates) {

    public UpdateStagePlanRequest(Integer progressPercent, String name, Integer weight, BigDecimal budgetAmount, Boolean clearBudget) {
        this(progressPercent, name, weight, budgetAmount, clearBudget, null, null, null, null);
    }

    public boolean shouldClearPlannedDates() {
        return Boolean.TRUE.equals(clearPlannedDates);
    }

    /** Ausente equivale a no borrar. */
    public boolean shouldClearBudget() {
        return Boolean.TRUE.equals(clearBudget);
    }
}