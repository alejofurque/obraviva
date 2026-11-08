package com.obraviva.platform.developments;

import com.obraviva.platform.developments.StageCatalogService.StagePlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OverallProgressTest {

    private StagePlan stage(int progress, int weight) {
        return new StagePlan(UUID.randomUUID(), "Etapa", 0, "IN_PROGRESS", progress, weight, null, null, null, null);
    }

    @Test
    @DisplayName("una obra sin etapas declara cero, no falla")
    void emptyDevelopmentIsZero() {
        assertThat(StageCatalogService.overallProgress(List.of()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("con pesos iguales el avance es el promedio")
    void equalWeightsAverage() {
        assertThat(StageCatalogService.overallProgress(
                List.of(stage(100, 1), stage(0, 1))))
                .isEqualByComparingTo(new BigDecimal("50.0"));
    }

    @Test
    @DisplayName("el peso manda: una etapa pesada arrastra el total")
    void weightDominates() {
        // Excavación terminada pero liviana, estructura apenas empezada y pesada.
        assertThat(StageCatalogService.overallProgress(
                List.of(stage(100, 1), stage(10, 9))))
                .isEqualByComparingTo(new BigDecimal("19.0"));
    }

    @Test
    @DisplayName("los pesos no necesitan sumar cien")
    void weightsNeedNotSumToOneHundred() {
        // 3 y 7 suman 10, no 100: el cálculo divide por la suma real.
        assertThat(StageCatalogService.overallProgress(
                List.of(stage(100, 3), stage(50, 7))))
                .isEqualByComparingTo(new BigDecimal("65.0"));
    }

    @Test
    @DisplayName("todo terminado es cien por ciento")
    void allCompleteIsOneHundred() {
        assertThat(StageCatalogService.overallProgress(
                List.of(stage(100, 2), stage(100, 5))))
                .isEqualByComparingTo(new BigDecimal("100.0"));
    }
}