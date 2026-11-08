package com.obraviva.platform.reports.narration;

import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.ReportDataCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NarrativeGuardTest {

    private final NarrativeGuard guard = new NarrativeGuard(new ReportDataCodec());

    private ReportData data() {
        return new ReportData(
                "Torre Sur",
                null,
                "2026-08-01",
                "2026-08-31",
                List.of(new ReportData.StageState("11111111-1111-1111-1111-111111111111",
                        "Estructura", 1, "IN_PROGRESS")),
                List.of(new ReportData.ProgressItem(
                        "2026-08-12", "Estructura", "Se hormigono la losa.", "MANUAL")),
                List.of(),
                new ReportData.Totals(1, 0, 0, 1, 0, 0));
    }

    @Test
    @DisplayName("acepta un texto cuyas cifras están en los datos")
    void acceptsNumbersPresentInTheSnapshot() {
        String narrative = "Entre el 2026-08-01 y el 2026-08-31 se registró 1 avance. "
                + "El 2026-08-12 se hormigonó la losa.";

        assertThatCode(() -> guard.verify(narrative, data())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rechaza una cifra que el modelo inventó")
    void rejectsInventedNumbers() {
        // El 35 no sale de ningún dato: es exactamente la síntesis que el prompt
        // prohíbe y que un modelo produce con naturalidad.
        String narrative = "La obra alcanzó un 35 por ciento de avance en el período.";

        assertThatThrownBy(() -> guard.verify(narrative, data()))
                .isInstanceOf(NarrationException.class)
                .hasMessageContaining("35");
    }

    @Test
    @DisplayName("un texto sin dígitos siempre pasa")
    void textWithoutDigitsPasses() {
        String narrative = "Durante el período se avanzó con la estructura del edificio.";

        assertThatCode(() -> guard.verify(narrative, data())).doesNotThrowAnyException();
    }
}