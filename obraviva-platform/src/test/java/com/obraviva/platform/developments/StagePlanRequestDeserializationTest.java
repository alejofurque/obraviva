package com.obraviva.platform.developments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización del cuerpo de la declaración de plan.
 *
 * <p>Existe por un bug real: {@code clearBudget} era un boolean primitivo y Jackson 3
 * rechazaba con 400 cualquier cuerpo que no lo mencionara. Ningún test lo detectó
 * porque todos construían el record en Java, con sus cuatro componentes, sin pasar
 * nunca por JSON. Un DTO que llega del exterior se prueba desde el JSON que manda el
 * cliente, no desde el constructor.
 */
class StagePlanRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("un cuerpo sin clearBudget se acepta y no borra el presupuesto")
    void bodyWithoutClearBudgetIsAccepted() {
        UpdateStagePlanRequest request = JSON.readValue(
                """
                {"progressPercent":100,"weight":2,"budgetAmount":8000000}""",
                UpdateStagePlanRequest.class);

        assertThat(request.progressPercent()).isEqualTo(100);
        assertThat(request.weight()).isEqualTo(2);
        assertThat(request.budgetAmount()).isEqualByComparingTo("8000000");
        assertThat(request.shouldClearBudget()).isFalse();
    }

    @Test
    @DisplayName("declarar sólo el avance deja el resto sin tocar")
    void progressOnlyBodyIsAccepted() {
        UpdateStagePlanRequest request = JSON.readValue(
                """
                {"progressPercent":45}""",
                UpdateStagePlanRequest.class);

        assertThat(request.progressPercent()).isEqualTo(45);
        assertThat(request.weight()).isNull();
        assertThat(request.budgetAmount()).isNull();
        assertThat(request.shouldClearBudget()).isFalse();
    }

    @Test
    @DisplayName("clearBudget en true sí borra")
    void clearBudgetIsHonoured() {
        UpdateStagePlanRequest request = JSON.readValue(
                """
                {"clearBudget":true}""",
                UpdateStagePlanRequest.class);

        assertThat(request.shouldClearBudget()).isTrue();
    }
}