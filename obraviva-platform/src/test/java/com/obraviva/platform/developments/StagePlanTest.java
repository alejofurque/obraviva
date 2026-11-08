package com.obraviva.platform.developments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagePlanTest {

    private Stage stage() {
        Development development = new Development(
                java.util.UUID.randomUUID(), "Torre Test", null, DevelopmentKind.MULTI_UNIT);
        return new Stage(development, "Estructura", 0);
    }

    @Test
    @DisplayName("una etapa nace sin avance y con peso uno")
    void stageStartsAtZero() {
        Stage stage = stage();

        assertThat(stage.getProgressPercent()).isZero();
        assertThat(stage.getWeight()).isEqualTo(1);
        assertThat(stage.getBudgetAmount()).isNull();
    }

    @Test
    @DisplayName("declarar avance no cambia el estado")
    void declaringProgressLeavesStatusAlone() {
        Stage stage = stage();
        stage.changeStatus(StageStatus.DELAYED);

        stage.declareProgress(60);

        // Una etapa puede estar al 60% y demorada: son dos declaraciones distintas.
        assertThat(stage.getProgressPercent()).isEqualTo(60);
        assertThat(stage.getStatus()).isEqualTo(StageStatus.DELAYED);
    }

    @Test
    @DisplayName("llegar a cien tampoco completa la etapa sola")
    void fullProgressDoesNotCompleteTheStage() {
        Stage stage = stage();

        stage.declareProgress(100);

        assertThat(stage.getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    @DisplayName("el avance fuera de rango se rechaza")
    void progressOutOfRangeIsRejected() {
        Stage stage = stage();

        assertThatThrownBy(() -> stage.declareProgress(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage.declareProgress(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el peso cero se rechaza: anularía la etapa del cálculo")
    void zeroWeightIsRejected() {
        assertThatThrownBy(() -> stage().changeWeight(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el presupuesto se puede fijar y borrar")
    void budgetCanBeSetAndCleared() {
        Stage stage = stage();

        stage.changeBudget(new BigDecimal("1500000.00"));
        assertThat(stage.getBudgetAmount()).isEqualByComparingTo("1500000.00");

        stage.changeBudget(null);
        assertThat(stage.getBudgetAmount()).isNull();
    }
}