package com.obraviva.platform.ai.extraction.mock;

import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ExtractionException;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.StageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockProgressExtractorTest {

    private final MockProgressExtractor extractor = new MockProgressExtractor();

    private final UUID electricalId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();

    private final List<StageContext> stages = List.of(
            new StageContext(electricalId, "Instalación eléctrica", 1, "PENDING"),
            new StageContext(structureId, "Estructura", 2, "IN_PROGRESS"));

    /** Transcripción real obtenida del audio de prueba, con sus errores incluidos. */
    private static final String REAL_TRANSCRIPT = """
            Hola, te mando el avance de esta semana. Terminamos el rego que gruesos \
            los departamentos del segundo piso. Falta todavía la instalación eléctrica \
            del tercero. Se demoró porque no llegaron los caños corrobados. La estructura \
            del cuarto piso arranca el lunes.""";

    @Test
    @DisplayName("propone cambios sólo para las etapas mencionadas en el audio")
    void proposesOnlyMentionedStages() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest(REAL_TRANSCRIPT, stages));

        assertThat(result.stageProposals())
                .extracting(p -> p.stageId())
                .containsExactlyInAnyOrder(electricalId, structureId);
    }

    @Test
    @DisplayName("todas las referencias apuntan a etapas existentes")
    void neverInventsStages() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest(REAL_TRANSCRIPT, stages));

        List<UUID> validIds = List.of(electricalId, structureId);
        assertThat(result.stageProposals()).allSatisfy(
                p -> assertThat(validIds).contains(p.stageId()));
    }

    @Test
    @DisplayName("detecta la demora mencionada en el audio")
    void detectsDelay() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest(REAL_TRANSCRIPT, stages));

        assertThat(result.stageProposals())
                .allSatisfy(p -> assertThat(p.proposedStatus()).isEqualTo("DELAYED"));
    }

    @Test
    @DisplayName("detecta la mención de materiales como alerta de insumos")
    void detectsSupplyAlert() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest(REAL_TRANSCRIPT, stages));

        assertThat(result.supplyAlerts()).isNotEmpty();
    }

    @Test
    @DisplayName("sin etapas mencionadas no propone cambios de estado")
    void proposesNothingWhenNoStageIsMentioned() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest("Hoy no hubo novedades en la obra.", stages));

        assertThat(result.stageProposals()).isEmpty();
    }

    @Test
    @DisplayName("registra la versión de prompt y el modelo para trazabilidad")
    void recordsTraceability() {
        AudioExtraction result = extractor.extract(
                new ExtractionRequest(REAL_TRANSCRIPT, stages));

        assertThat(result.promptVersion()).isEqualTo(MockProgressExtractor.PROMPT_VERSION);
        assertThat(result.modelId()).isEqualTo(MockProgressExtractor.MODEL_ID);
    }

    @Test
    @DisplayName("una transcripción vacía es un error técnico")
    void emptyTranscriptFails() {
        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest("   ", stages)))
                .isInstanceOf(ExtractionException.class);
    }
}