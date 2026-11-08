package com.obraviva.platform.ai.extraction.gemini;

import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ConfidenceLevel;
import com.obraviva.platform.ai.extraction.ExtractionException;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.StageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador real contra respuestas preparadas. No hay red, ni contexto de
 * Spring, ni consumo de cuota: lo que se ejercita es el armado del prompt y el
 * análisis de la respuesta, que es donde se concentran los errores.
 */
class GeminiProgressExtractorTest {

    private static final String PROMPT_VERSION = "v1";
    private static final String MODEL_ID = "gemini-2.5-flash";

    private final UUID electricalId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();

    private final List<StageContext> stages = List.of(
            new StageContext(electricalId, "Instalación eléctrica", 1, "PENDING"),
            new StageContext(structureId, "Estructura", 2, "IN_PROGRESS"));

    private static String promptTemplate() throws IOException {
        try (var in = GeminiProgressExtractorTest.class.getClassLoader()
                .getResourceAsStream("prompts/progress-extraction-v1.txt")) {
            assertThat(in).as("La plantilla de prompt debe estar en los recursos").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private GeminiProgressExtractor extractorReturning(String response) throws IOException {
        return new GeminiProgressExtractor(
                prompt -> response, promptTemplate(), PROMPT_VERSION, MODEL_ID);
    }

    private String validResponse() {
        return """
                {
                  "stageProposals": [
                    {
                      "stageId": "%s",
                      "proposedStatus": "DELAYED",
                      "rationale": "se demoró porque no llegaron los caños",
                      "confidence": "HIGH"
                    }
                  ],
                  "progressNotes": [
                    {
                      "stageId": null,
                      "description": "Se terminó el revoque grueso del segundo piso",
                      "confidence": "MEDIUM"
                    }
                  ],
                  "supplyAlerts": [
                    {
                      "description": "Faltan caños corrugados",
                      "confidence": "HIGH"
                    }
                  ]
                }""".formatted(electricalId);
    }

    @Test
    @DisplayName("interpreta una respuesta bien formada")
    void parsesWellFormedResponse() throws IOException {
        AudioExtraction result = extractorReturning(validResponse())
                .extract(new ExtractionRequest("texto del audio", stages));

        assertThat(result.stageProposals()).hasSize(1);
        assertThat(result.stageProposals().getFirst().stageId()).isEqualTo(electricalId);
        assertThat(result.stageProposals().getFirst().proposedStatus()).isEqualTo("DELAYED");
        assertThat(result.stageProposals().getFirst().confidence()).isEqualTo(ConfidenceLevel.HIGH);

        assertThat(result.progressNotes()).hasSize(1);
        assertThat(result.progressNotes().getFirst().stageId()).isNull();

        assertThat(result.supplyAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("el prompt incluye las etapas con su identificador y la transcripción")
    void promptCarriesStagesAndTranscript() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        GeminiProgressExtractor extractor = new GeminiProgressExtractor(
                prompt -> {
                    captured.set(prompt);
                    return validResponse();
                },
                promptTemplate(), PROMPT_VERSION, MODEL_ID);

        extractor.extract(new ExtractionRequest("terminamos el revoque grueso", stages));

        // Sin los identificadores en el prompt, el modelo no tiene forma de
        // referenciar etapas existentes y terminaría inventándolas.
        assertThat(captured.get()).contains(electricalId.toString());
        assertThat(captured.get()).contains(structureId.toString());
        assertThat(captured.get()).contains("Instalación eléctrica");
        assertThat(captured.get()).contains("terminamos el revoque grueso");
        assertThat(captured.get()).doesNotContain("{{ETAPAS}}");
        assertThat(captured.get()).doesNotContain("{{TRANSCRIPCION}}");
    }

    @Test
    @DisplayName("tolera que el modelo envuelva el JSON en un bloque de código")
    void toleratesCodeFence() throws IOException {
        String wrapped = "```json\n" + validResponse() + "\n```";

        AudioExtraction result = extractorReturning(wrapped)
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.stageProposals()).hasSize(1);
    }

    @Test
    @DisplayName("tolera texto adicional antes y después del JSON")
    void toleratesSurroundingProse() throws IOException {
        String noisy = "Claro, acá va el análisis:\n" + validResponse() + "\nEspero que sirva.";

        AudioExtraction result = extractorReturning(noisy)
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.supplyAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("un identificador mal formado no aborta la interpretación")
    void malformedUuidBecomesNull() throws IOException {
        String response = """
                {
                  "stageProposals": [
                    {"stageId": "etapa-3", "proposedStatus": "DELAYED",
                     "rationale": "x", "confidence": "HIGH"}
                  ],
                  "progressNotes": [],
                  "supplyAlerts": []
                }""";

        // Se conserva el elemento con identificador nulo: descartarlo es decisión
        // del validador, que es quien conoce el dominio.
        AudioExtraction result = extractorReturning(response)
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.stageProposals()).hasSize(1);
        assertThat(result.stageProposals().getFirst().stageId()).isNull();
    }

    @Test
    @DisplayName("una confianza desconocida se degrada a la más baja")
    void unknownConfidenceBecomesLow() throws IOException {
        String response = """
                {
                  "stageProposals": [],
                  "progressNotes": [
                    {"stageId": null, "description": "algo", "confidence": "bastante segura"}
                  ],
                  "supplyAlerts": []
                }""";

        AudioExtraction result = extractorReturning(response)
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.progressNotes().getFirst().confidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("listas ausentes se interpretan como vacías")
    void missingArraysBecomeEmpty() throws IOException {
        AudioExtraction result = extractorReturning("{\"stageProposals\": []}")
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("conserva la respuesta cruda y la trazabilidad")
    void preservesRawOutputAndTraceability() throws IOException {
        String response = validResponse();

        AudioExtraction result = extractorReturning(response)
                .extract(new ExtractionRequest("texto", stages));

        assertThat(result.rawOutput()).isEqualTo(response);
        assertThat(result.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(result.modelId()).isEqualTo(MODEL_ID);
    }

    @Test
    @DisplayName("una respuesta que no es JSON es un error técnico")
    void nonJsonResponseFails() throws IOException {
        assertThatThrownBy(() -> extractorReturning("Perdón, no puedo ayudarte con eso.")
                .extract(new ExtractionRequest("texto", stages)))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    @DisplayName("una respuesta vacía es un error técnico")
    void emptyResponseFails() throws IOException {
        assertThatThrownBy(() -> extractorReturning("   ")
                .extract(new ExtractionRequest("texto", stages)))
                .isInstanceOf(ExtractionException.class);
    }

    @Test
    @DisplayName("un fallo del proveedor se reporta como error de interpretación")
    void providerFailureIsWrapped() throws IOException {
        GeminiProgressExtractor extractor = new GeminiProgressExtractor(
                prompt -> {
                    throw new IllegalStateException("429 Too Many Requests");
                },
                promptTemplate(), PROMPT_VERSION, MODEL_ID);

        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("texto", stages)))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("una transcripción vacía se rechaza sin llamar al proveedor")
    void emptyTranscriptDoesNotCallProvider() throws IOException {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        GeminiProgressExtractor extractor = new GeminiProgressExtractor(
                prompt -> {
                    called.set(true);
                    return validResponse();
                },
                promptTemplate(), PROMPT_VERSION, MODEL_ID);

        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("  ", stages)))
                .isInstanceOf(ExtractionException.class);
        assertThat(called.get()).isFalse();
    }
}