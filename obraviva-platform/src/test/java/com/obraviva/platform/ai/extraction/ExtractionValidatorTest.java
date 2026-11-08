package com.obraviva.platform.ai.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests del validador de propuestas. Es la pieza que impide que una alucinación del
 * modelo llegue a la pantalla de revisión, así que se prueba exhaustivamente: no
 * necesita Spring, red ni proveedor.
 */
class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    private final UUID stageA = UUID.randomUUID();
    private final UUID stageB = UUID.randomUUID();

    private final List<StageContext> stages = List.of(
            new StageContext(stageA, "Estructura", 1, "IN_PROGRESS"),
            new StageContext(stageB, "Instalación eléctrica", 2, "PENDING"));

    private AudioExtraction extraction(
            List<StageProposal> proposals, List<ProgressNote> notes, List<SupplyAlert> alerts) {
        return new AudioExtraction(proposals, notes, alerts, "v1", "modelo-x", "{}");
    }

    @Test
    @DisplayName("conserva una propuesta válida y normaliza el estado a mayúsculas")
    void keepsValidProposal() {
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(new StageProposal(stageA, "delayed", "se demoró", ConfidenceLevel.HIGH)),
                        List.of(), List.of()),
                stages);

        assertThat(result.stageProposals()).hasSize(1);
        assertThat(result.stageProposals().getFirst().proposedStatus()).isEqualTo("DELAYED");
        assertThat(result.stageProposals().getFirst().confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("descarta una propuesta que referencia una etapa inexistente")
    void discardsProposalForUnknownStage() {
        // Es el caso central: el modelo inventó un identificador. El revisor no
        // tendría cómo reconocerlo ni contrastarlo contra la obra.
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(new StageProposal(
                                UUID.randomUUID(), "COMPLETED", "inventado", ConfidenceLevel.HIGH)),
                        List.of(), List.of()),
                stages);

        assertThat(result.stageProposals()).isEmpty();
    }

    @Test
    @DisplayName("descarta una propuesta con un estado que no existe en el dominio")
    void discardsProposalWithInvalidStatus() {
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(new StageProposal(
                                stageA, "CASI_LISTO", "estado inventado", ConfidenceLevel.HIGH)),
                        List.of(), List.of()),
                stages);

        assertThat(result.stageProposals()).isEmpty();
    }

    @Test
    @DisplayName("acepta los cuatro estados del dominio y ninguno más")
    void acceptsOnlyDomainStatuses() {
        List<StageProposal> proposals = List.of(
                new StageProposal(stageA, "PENDING", "a", ConfidenceLevel.HIGH),
                new StageProposal(stageA, "IN_PROGRESS", "b", ConfidenceLevel.HIGH),
                new StageProposal(stageA, "COMPLETED", "c", ConfidenceLevel.HIGH),
                new StageProposal(stageA, "DELAYED", "d", ConfidenceLevel.HIGH),
                new StageProposal(stageA, "CANCELLED", "e", ConfidenceLevel.HIGH));

        AudioExtraction result = validator.validate(
                extraction(proposals, List.of(), List.of()), stages);

        assertThat(result.stageProposals()).hasSize(4);
    }

    @Test
    @DisplayName("una novedad con etapa inexistente se conserva, pero sin etapa")
    void keepsNoteButDropsUnknownStage() {
        // A diferencia de las propuestas de estado, el modelo de datos admite
        // avances sin etapa asociada: la novedad sigue siendo información útil.
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(),
                        List.of(new ProgressNote(
                                UUID.randomUUID(), "Se hormigonó la losa", ConfidenceLevel.MEDIUM)),
                        List.of()),
                stages);

        assertThat(result.progressNotes()).hasSize(1);
        assertThat(result.progressNotes().getFirst().stageId()).isNull();
        assertThat(result.progressNotes().getFirst().description()).isEqualTo("Se hormigonó la losa");
    }

    @Test
    @DisplayName("descarta novedades y alertas sin descripción")
    void discardsEmptyDescriptions() {
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(),
                        List.of(new ProgressNote(stageA, "   ", ConfidenceLevel.HIGH)),
                        List.of(new SupplyAlert(null, ConfidenceLevel.HIGH))),
                stages);

        assertThat(result.progressNotes()).isEmpty();
        assertThat(result.supplyAlerts()).isEmpty();
    }

    @Test
    @DisplayName("una confianza ausente se degrada a la más baja")
    void missingConfidenceBecomesLow() {
        // Ante la duda sobre lo que dijo el modelo, que el revisor lo mire.
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(new StageProposal(stageA, "DELAYED", "motivo", null)),
                        List.of(new ProgressNote(stageA, "algo", null)),
                        List.of(new SupplyAlert("faltan caños", null))),
                stages);

        assertThat(result.stageProposals().getFirst().confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(result.progressNotes().getFirst().confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(result.supplyAlerts().getFirst().confidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("recorta descripciones desmedidas")
    void truncatesOversizedDescriptions() {
        String huge = "x".repeat(2000);

        AudioExtraction result = validator.validate(
                extraction(
                        List.of(),
                        List.of(new ProgressNote(stageA, huge, ConfidenceLevel.HIGH)),
                        List.of()),
                stages);

        assertThat(result.progressNotes().getFirst().description()).hasSize(500);
    }

    @Test
    @DisplayName("conserva la trazabilidad de prompt y modelo")
    void preservesTraceability() {
        AudioExtraction result = validator.validate(
                extraction(List.of(), List.of(), List.of()), stages);

        assertThat(result.promptVersion()).isEqualTo("v1");
        assertThat(result.modelId()).isEqualTo("modelo-x");
        assertThat(result.rawOutput()).isEqualTo("{}");
    }

    @Test
    @DisplayName("sin etapas disponibles se descartan todas las propuestas de estado")
    void withoutStagesAllProposalsAreDiscarded() {
        AudioExtraction result = validator.validate(
                extraction(
                        List.of(new StageProposal(stageA, "DELAYED", "motivo", ConfidenceLevel.HIGH)),
                        List.of(), List.of()),
                List.of());

        assertThat(result.stageProposals()).isEmpty();
    }
}