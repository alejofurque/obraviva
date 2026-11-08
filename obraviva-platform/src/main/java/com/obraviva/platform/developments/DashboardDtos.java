package com.obraviva.platform.developments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Panel privado del desarrollista. Nada de esto sale en la vista pública ni en "Mi
 * propiedad": el control económico es interno, como establece la sección 5 del
 * documento maestro.
 *
 * <p>Todas las cifras las calcula el motor de datos. Ninguna proviene de un modelo de
 * lenguaje.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * Cómo se compara lo gastado con lo avanzado.
     *
     * <p>SIN_PRESUPUESTO no es "está bien": es que no hay contra qué comparar. Pintar
     * de verde una etapa sin presupuesto afirmaría algo que nadie declaró.
     */
    public enum TrafficLight {
        GREEN,
        YELLOW,
        RED,
        NO_BUDGET
    }

    public record StageDashboardRow(
            UUID stageId,
            String name,
            int orderIndex,
            String status,
            int progressPercent,
            int weight,
            BigDecimal budgetAmount,
            BigDecimal spentAmount,
            /** Porcentaje del presupuesto consumido. Null si no hay presupuesto. */
            BigDecimal budgetUsedPercent,
            TrafficLight trafficLight) {
    }

    /**
     * Una casa en el panel del desarrollo: su avance contra su gasto, con el mismo semáforo
     * que una etapa.
     *
     * <p>{@code worstStageLight} es el peor semáforo entre sus etapas (rojo, amarillo, verde,
     * sin presupuesto, en ese orden), y {@code worstStageName} la etapa que lo enciende, sólo
     * cuando es una alerta: el total de la casa puede ir bien mientras una etapa ya se fue.
     *
     * <p>La misma fila describe las obras comunes, con {@code unitId} y {@code code} nulos.
     * {@code stagesWithoutBudget} de {@code stageCount}: con alguna sin presupuesto, el costo
     * final estimado no se compara con el presupuesto.
     */
    public record UnitDashboardRow(
            UUID unitId,
            String code,
            int progressPercent,
            BigDecimal budgetAmount,
            BigDecimal spentAmount,
            BigDecimal budgetUsedPercent,
            TrafficLight trafficLight,
            TrafficLight worstStageLight,
            String worstStageName,
            int stagesWithoutBudget,
            int stageCount) {
    }

    /** Una etapa para elegir al cargar un gasto o para nombrarlo: "Casa 1 · Estructura". */
    public record StageOption(UUID stageId, UUID unitId, String name, int orderIndex) {
    }

    /**
     * Una etapa en alerta (amarillo o rojo). {@code unitCode} nulo: es de las obras comunes.
     */
    public record AlertStage(UUID stageId, String name, UUID unitId, String unitCode, TrafficLight trafficLight,
                             /** Para "Gastó 62% del presupuesto con 30% de avance". Nulo sin presupuesto. */
                             BigDecimal budgetUsedPercent, Integer progressPercent) {
    }

    /** Un punto de la serie de gasto acumulado, por mes. */
    public record SpendingPoint(String month, BigDecimal monthlyAmount, BigDecimal accumulated) {
    }

    /**
     * Panel de una casa ({@code unitId} con valor: sus etapas y sus gastos) o del desarrollo
     * entero ({@code unitId} nulo: totales de todo, las etapas de obras comunes y una fila
     * por casa).
     */
    public record DashboardResponse(
            UUID developmentId,
            UUID unitId,
            String developmentName,
            /** Avance físico global, ponderado por el peso de cada etapa. */
            BigDecimal overallProgressPercent,
            BigDecimal totalBudget,
            BigDecimal totalSpent,
            /** Porcentaje del presupuesto total consumido. Null si no hay presupuesto. */
            BigDecimal budgetUsedPercent,
            /** Gastos sin etapa: entran en el total pero no en ningún semáforo. */
            BigDecimal unassignedSpent,
            List<StageDashboardRow> stages,
            List<SpendingPoint> spendingCurve,
            LocalDate lastExpenseOn,
            List<UnitDashboardRow> units,
            /**
             * Etapas en alerta del alcance: en una casa, las suyas; en el desarrollo, las de
             * todas las casas más las de obras comunes. Primero las rojas, después en orden
             * de casa y de etapa.
             */
            List<AlertStage> alerts,
            /**
             * Gasto previsto por mes: el presupuesto de cada etapa repartido por días entre su
             * inicio y su fin previstos, y acumulado. Las etapas sin fechas o sin presupuesto no
             * entran. Vacía si ninguna etapa tiene las dos cosas.
             */
            List<SpendingPoint> plannedCurve,
            /** Etapas con presupuesto que quedan afuera de la curva por no tener fechas previstas. */
            int undatedStages,
            BigDecimal undatedBudget,
            /**
             * En el desarrollo, las obras comunes como una fila más: sus etapas y los gastos
             * cargados en ellas. Nula en una casa o si el desarrollo no tiene obras comunes.
             */
            UnitDashboardRow commonWorks,
            /** Gastos del desarrollo sin casa ni etapa ("General del desarrollo"); cero en una casa. */
            BigDecimal generalSpent,
            /** Las etapas del alcance, de todas las casas y las comunes en el desarrollo, en orden de obra. */
            List<StageOption> stageOptions) {
    }
}