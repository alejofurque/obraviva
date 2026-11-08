package com.obraviva.platform.overview;

import com.obraviva.platform.activity.ActivityDtos.ActivityItem;
import com.obraviva.platform.developments.DashboardDtos.TrafficLight;
import com.obraviva.platform.developments.DevelopmentKind;
import com.obraviva.platform.developments.UnitResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OverviewDtos {

    private OverviewDtos() {
    }

    /**
     * Resumen del desarrollo. {@code economic}, {@code commercial} y {@code setup} son nulos
     * para el encargado.
     */
    public record DevelopmentOverviewResponse(
            UUID developmentId,
            DevelopmentKind kind,
            ContentOverview content,
            EconomicOverview economic,
            CommercialOverview commercial,
            List<SetupStep> setup,
            /** Una tarjeta por casa, en el orden del listado de casas. */
            List<HouseCard> houses) {
    }

    /**
     * Lo que dice la tarjeta de una casa en el Resumen del desarrollo, además de la casa misma
     * (que llega en el listado de casas): la etapa en curso principal y cuántas más se están
     * trabajando, cuándo arranca si todavía no arrancó, lo último que pasó (con el formato de
     * Novedades) y, sólo para el dueño, si eso está sin ver, si tiene encargado y su peor
     * alerta de gasto. Para el encargado, esos tres llegan nulos.
     */
    public record HouseCard(
            UUID unitId,
            String currentStageName,
            Integer otherRunningStages,
            LocalDate plannedStart,
            ActivityItem lastActivity,
            Boolean unseen,
            Boolean hasSupervisor,
            TrafficLight alertLight,
            String alertStageName) {
    }

    /**
     * Resumen de una casa: su ficha, lo que tiene publicado y lo que le falta para
     * mostrarse. {@code economic} y {@code setup} son nulos para el encargado, y la ficha le
     * llega sin precio ni compradores.
     */
    public record UnitOverviewResponse(
            UUID unitId,
            UUID developmentId,
            UnitResponse unit,
            ContentOverview content,
            EconomicOverview economic,
            List<SetupStep> setup) {
    }

    /** Lo que tiene publicado o en camino. Lo ven dueño y encargado. */
    public record ContentOverview(
            int stages,
            int progressEntries,
            int photos,
            /** Fotos de galería: las que se cargan para mostrar la casa, sin avance. */
            int galleryPhotos,
            int capturesAvailable,
            int capturesInProcess,
            int capturesFailed,
            int audiosToReview,
            LocalDate lastEntryDate,
            LocalDate lastCaptureDate) {
    }

    public record EconomicOverview(
            BigDecimal totalBudget,
            BigDecimal totalSpent,
            /** Nulo si no hay presupuesto cargado. */
            BigDecimal budgetUsedPercent,
            int stagesWithBudget) {
    }

    public record CommercialOverview(
            int units,
            int available,
            int reserved,
            int sold,
            int unitsWithBuyer,
            int withDetails,
            int withCover,
            int withStages,
            int withPrice) {
    }

    /** Un paso de la puesta en marcha. {@code key} es estable: la interfaz lo usa para enlazar. */
    public record SetupStep(String key, String title, String description, boolean done) {
    }
}
