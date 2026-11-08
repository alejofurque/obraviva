package com.obraviva.platform.reports.narration.gemini;

import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.ReportDataCodec;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador real contra respuestas preparadas. No hay red, ni contexto de
 * Spring, ni consumo de cuota: lo que se ejercita es el armado del prompt y el
 * tratamiento de la respuesta.
 */
class GeminiReportNarratorTest {

    private static final String PROMPT_VERSION = "v1";
    private static final String MODEL_ID = "gemini-3.6-flash";

    private final ReportDataCodec codec = new ReportDataCodec();

    private static String promptTemplate() throws IOException {
        try (var in = GeminiReportNarratorTest.class.getClassLoader()
                .getResourceAsStream("prompts/report-narration-v1.txt")) {
            assertThat(in).as("La plantilla de prompt debe estar en los recursos").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ReportData data() {
        return new ReportData(
                "Torre Sur",
                "Av. Colon 100",
                "2026-08-01",
                "2026-08-31",
                List.of(new ReportData.StageState("11111111-1111-1111-1111-111111111111",
                        "Estructura", 1, "DELAYED")),
                List.of(new ReportData.ProgressItem(
                        "2026-08-12", "Estructura", "Se hormigono la losa.", "AUDIO")),
                List.of(),
                new ReportData.Totals(1, 0, 0, 0, 1, 0));
    }

    private GeminiReportNarrator narratorReturning(String response) throws IOException {
        return new GeminiReportNarrator(
                prompt -> response, codec, promptTemplate(), PROMPT_VERSION, MODEL_ID);
    }

    @Test
    @DisplayName("el prompt lleva el snapshot completo y ningún marcador sin reemplazar")
    void promptCarriesTheSnapshot() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        GeminiReportNarrator narrator = new GeminiReportNarrator(
                prompt -> {
                    captured.set(prompt);
                    return "Texto del informe.";
                },
                codec, promptTemplate(), PROMPT_VERSION, MODEL_ID);

        narrator.narrate(data());

        assertThat(captured.get()).contains("Torre Sur");
        assertThat(captured.get()).contains("Se hormigono la losa.");
        assertThat(captured.get()).contains("DELAYED");
        assertThat(captured.get()).doesNotContain("{{DATOS}}");
    }

    @Test
    @DisplayName("devuelve el texto con su trazabilidad y conserva la respuesta cruda")
    void returnsTextWithTraceability() throws IOException {
        Narration narration = narratorReturning("  Texto del informe.  ").narrate(data());

        assertThat(narration.text()).isEqualTo("Texto del informe.");
        assertThat(narration.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(narration.modelId()).isEqualTo(MODEL_ID);
        assertThat(narration.rawOutput()).contains("Texto del informe.");
    }

    @Test
    @DisplayName("tolera que el modelo envuelva la prosa en un bloque de código")
    void toleratesCodeFence() throws IOException {
        String wrapped = "```\nTexto del informe.\n```";

        Narration narration = narratorReturning(wrapped).narrate(data());

        assertThat(narration.text()).isEqualTo("Texto del informe.");
        // La respuesta cruda conserva el envoltorio: es lo que después distingue un
        // problema de formato de uno de contenido.
        assertThat(narration.rawOutput()).startsWith("```");
    }

    @Test
    @DisplayName("una respuesta vacía es un error de redacción")
    void emptyResponseFails() throws IOException {
        assertThatThrownBy(() -> narratorReturning("   ").narrate(data()))
                .isInstanceOf(NarrationException.class);
    }

    @Test
    @DisplayName("un fallo del proveedor se reporta como error de redacción")
    void providerFailureIsWrapped() throws IOException {
        GeminiReportNarrator narrator = new GeminiReportNarrator(
                prompt -> {
                    throw new IllegalStateException("429 Too Many Requests");
                },
                codec, promptTemplate(), PROMPT_VERSION, MODEL_ID);

        assertThatThrownBy(() -> narrator.narrate(data()))
                .isInstanceOf(NarrationException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("sin snapshot no se llama al proveedor")
    void missingSnapshotDoesNotCallProvider() throws IOException {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        GeminiReportNarrator narrator = new GeminiReportNarrator(
                prompt -> {
                    called.set(true);
                    return "Texto.";
                },
                codec, promptTemplate(), PROMPT_VERSION, MODEL_ID);

        assertThatThrownBy(() -> narrator.narrate(null))
                .isInstanceOf(NarrationException.class);
        assertThat(called.get()).isFalse();
    }
}