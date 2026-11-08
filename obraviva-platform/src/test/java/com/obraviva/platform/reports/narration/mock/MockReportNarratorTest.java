package com.obraviva.platform.reports.narration.mock;

import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockReportNarratorTest {

    private final MockReportNarrator narrator = new MockReportNarrator();

    private ReportData sample() {
        return new ReportData(
                "Torre Sur",
                "Av. Colon 100",
                "2026-08-01",
                "2026-08-31",
                List.of(
                        new ReportData.StageState("11111111-1111-1111-1111-111111111111",
                                "Excavacion", 0, "COMPLETED"),
                        new ReportData.StageState("22222222-2222-2222-2222-222222222222",
                                "Estructura", 1, "DELAYED")),
                List.of(new ReportData.ProgressItem(
                        "2026-08-12", "Estructura", "Se hormigono la losa del cuarto piso.", "AUDIO")),
                List.of(new ReportData.CaptureItem(
                        "33333333-3333-3333-3333-333333333333", "2026-08-20", "Recorrido del segundo piso")),
                new ReportData.Totals(1, 1, 1, 0, 1, 0));
    }

    @Test
    @DisplayName("el texto se construye con los datos del snapshot, no con datos fijos")
    void narrativeIsBuiltFromTheSnapshot() {
        Narration narration = narrator.narrate(sample());

        assertThat(narration.text())
                .contains("Torre Sur")
                .contains("2026-08-01")
                .contains("2026-08-31")
                .contains("Se hormigono la losa del cuarto piso.")
                .contains("Recorrido del segundo piso");
        // Las demoras se nombran: es una decisión de producto, no una omisión amable.
        assertThat(narration.text()).contains("1 demoradas");
        assertThat(narration.modelId()).isEqualTo(MockReportNarrator.MODEL_ID);
        assertThat(narration.promptVersion()).isEqualTo(MockReportNarrator.PROMPT_VERSION);
    }

    @Test
    @DisplayName("sin snapshot no hay redacción posible")
    void missingSnapshotFails() {
        assertThatThrownBy(() -> narrator.narrate(null))
                .isInstanceOf(NarrationException.class);
    }
}