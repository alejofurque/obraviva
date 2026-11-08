package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DashboardDtos.SpendingPoint;
import com.obraviva.platform.developments.StageCatalogService.StagePlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlannedCurveTest {

    private static StagePlan stage(String budget, String start, String end) {
        return new StagePlan(UUID.randomUUID(), "Etapa", 1, "PENDING", 0, 1,
                budget == null ? null : new BigDecimal(budget), null,
                start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end));
    }

    @Test
    @DisplayName("reparte el presupuesto de cada etapa por días entre su inicio y su fin, y acumula")
    void spreadsByDays() {
        List<SpendingPoint> curve = DashboardService.plannedCurve(List.of(
                // 31 días: 17 en enero y 14 en febrero.
                stage("3100", "2026-01-15", "2026-02-14"),
                stage("900", "2026-02-01", "2026-02-28")));

        assertThat(curve).extracting(SpendingPoint::month).containsExactly("2026-01", "2026-02");
        assertThat(curve.get(0).monthlyAmount()).isEqualByComparingTo("1700");
        assertThat(curve.get(1).monthlyAmount()).isEqualByComparingTo("2300");
        assertThat(curve.get(1).accumulated()).isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("una etapa sin fechas, con las fechas al revés o sin presupuesto queda afuera")
    void leavesOutWhatCannotBePlanned() {
        List<SpendingPoint> curve = DashboardService.plannedCurve(List.of(
                stage("5000", null, "2026-03-01"),
                stage("5000", "2026-04-01", "2026-03-01"),
                stage(null, "2026-01-01", "2026-01-31"),
                stage("620", "2026-03-01", "2026-03-31")));

        assertThat(curve).singleElement().satisfies(point -> {
            assertThat(point.month()).isEqualTo("2026-03");
            assertThat(point.accumulated()).isEqualByComparingTo("620");
        });
    }

    @Test
    @DisplayName("sin ninguna etapa con fechas y presupuesto, no hay curva prevista")
    void emptyWithoutPlan() {
        assertThat(DashboardService.plannedCurve(List.of(stage("1000", null, null)))).isEmpty();
    }
}
