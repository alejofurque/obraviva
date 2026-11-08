package com.obraviva.platform.developments;

import com.obraviva.platform.developments.ExpenseDtos.UpdateExpenseRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización de la corrección de un gasto desde el JSON que manda el formulario de
 * Económico. Sin primitivos: un campo que falta no puede romper la construcción del record.
 */
class UpdateExpenseRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("el cuerpo completo del formulario, con decimales en el monto")
    void fullBody() {
        UUID stageId = UUID.randomUUID();
        UpdateExpenseRequest request = JSON.readValue("""
                {"stageId":"%s","incurredOn":"2026-09-12","amount":1250000.5,"description":"Hierro del 8"}"""
                .formatted(stageId), UpdateExpenseRequest.class);

        assertThat(request.stageId()).isEqualTo(stageId);
        assertThat(request.incurredOn()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(request.amount()).isEqualByComparingTo(new BigDecimal("1250000.50"));
        assertThat(request.description()).isEqualTo("Hierro del 8");
    }

    @Test
    @DisplayName("un gasto general manda la etapa nula, y el concepto puede faltar")
    void generalExpenseWithoutConcept() {
        UpdateExpenseRequest request = JSON.readValue("""
                {"stageId":null,"incurredOn":"2026-09-12","amount":90000}""", UpdateExpenseRequest.class);

        assertThat(request.stageId()).isNull();
        assertThat(request.description()).isNull();
    }

    @Test
    @DisplayName("sin monto el record se arma igual y la validación lo rechaza, no Jackson")
    void missingAmountIsNullNotAnError() {
        UpdateExpenseRequest request = JSON.readValue("""
                {"incurredOn":"2026-09-12"}""", UpdateExpenseRequest.class);

        assertThat(request.amount()).isNull();
        assertThat(request.stageId()).isNull();
    }
}
