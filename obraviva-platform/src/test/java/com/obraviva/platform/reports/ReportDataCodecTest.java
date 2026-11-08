package com.obraviva.platform.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportDataCodecTest {

    private final ReportDataCodec codec = new ReportDataCodec();

    private ReportData sample() {
        return new ReportData(
                "Torre Sur",
                "Av. Siempre Viva 742",
                "2026-08-01",
                "2026-08-31",
                List.of(new ReportData.StageState("11111111-1111-1111-1111-111111111111",
                        "Estructura", 1, "IN_PROGRESS")),
                List.of(new ReportData.ProgressItem(
                        "2026-08-12", "Estructura", "Se hormigonó la losa del cuarto piso.", "AUDIO")),
                List.of(new ReportData.CaptureItem(
                        "22222222-2222-2222-2222-222222222222", "2026-08-20", "Recorrido del segundo piso")),
                new ReportData.Totals(1, 1, 0, 1, 0, 0));
    }

    @Test
    @DisplayName("el snapshot sobrevive la ida y vuelta a texto")
    void snapshotSurvivesRoundTrip() {
        ReportData decoded = codec.decode(codec.encode(sample()));

        // Las fechas viajan como texto ISO justamente para que esta prueba no dependa
        // del soporte de java.time del serializador.
        assertThat(decoded.periodStart()).isEqualTo("2026-08-01");
        assertThat(decoded.progressEntries()).singleElement()
                .satisfies(entry -> assertThat(entry.stageName()).isEqualTo("Estructura"));
        assertThat(decoded.totals().captures()).isEqualTo(1);
    }

    @Test
    @DisplayName("un snapshot ausente es un error explícito, no un objeto vacío")
    void missingSnapshotFailsLoudly() {
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(ReportException.class);
        assertThatThrownBy(() -> codec.decode("  ")).isInstanceOf(ReportException.class);
    }
}