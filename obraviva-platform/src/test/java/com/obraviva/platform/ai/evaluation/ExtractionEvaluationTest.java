package com.obraviva.platform.ai.evaluation;

import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ConfidenceLevel;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ProgressNote;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.ai.extraction.StageProposal;
import com.obraviva.platform.ai.extraction.SupplyAlert;
import com.obraviva.platform.ai.extraction.mock.MockProgressExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el motor de evaluación: que las métricas se calculen bien y que el
 * conjunto de casos sea legible y coherente.
 *
 * <p>No mide la calidad del modelo real. Eso ocurre en
 * {@code GeminiEvaluationManualTest}, que consume cuota y corre bajo demanda.
 */
class ExtractionEvaluationTest {

    /** Extractor que devuelve una propuesta preparada, para probar el medidor. */
    private static ProgressExtractor fixedExtractor(
            List<StageProposal> proposals, List<ProgressNote> notes, List<SupplyAlert> alerts) {
        return request -> new AudioExtraction(
                proposals, notes, alerts, "test", "test", "{}");
    }

    private EvaluationCase firstCase() {
        return ExtractionEvaluator.loadCases().cases().getFirst();
    }

    @Test
    @DisplayName("el conjunto de casos se carga y está bien formado")
    void caseSetIsWellFormed() {
        ExtractionEvaluator.CaseSet caseSet = ExtractionEvaluator.loadCases();

        assertThat(caseSet.cases()).isNotEmpty();
        assertThat(caseSet.cases()).allSatisfy(testCase -> {
            assertThat(testCase.id()).isNotBlank();
            assertThat(testCase.transcript()).isNotBlank();
            // Sin fundamento anotado, el conjunto no es auditable por un tercero.
            assertThat(testCase.notes()).isNotBlank();
            assertThat(testCase.stages()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("una propuesta perfecta da precisión y exhaustividad plenas")
    void perfectProposalScoresOne() {
        EvaluationCase testCase = firstCase();

        // Extractor que reproduce exactamente lo esperado, resolviendo la etapa por
        // nombre desde el contexto que recibe.
        ProgressExtractor perfect = request -> {
            StageContext structure = request.availableStages().stream()
                    .filter(s -> s.name().equalsIgnoreCase("Estructura"))
                    .findFirst().orElseThrow();
            return new AudioExtraction(
                    List.of(new StageProposal(
                            structure.stageId(), "IN_PROGRESS", "arranca el lunes", ConfidenceLevel.HIGH)),
                    List.of(
                            new ProgressNote(null, "Se terminaron los revoques gruesos", ConfidenceLevel.HIGH),
                            new ProgressNote(null, "Falta la instalación eléctrica", ConfidenceLevel.HIGH)),
                    List.of(
                            new SupplyAlert("No llegaron los caños corrugados", ConfidenceLevel.HIGH),
                            new SupplyAlert("Confirmar compra de sanitarios", ConfidenceLevel.HIGH)),
                    "test", "test", "{}");
        };

        EvaluationResult result = new ExtractionEvaluator(perfect).evaluate(testCase);

        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.f1()).isEqualTo(1.0);
        assertThat(result.hasHallucinations()).isFalse();
        assertThat(result.missingKeywords()).isEmpty();
    }

    @Test
    @DisplayName("omitir una propuesta baja la exhaustividad pero no la precisión")
    void omissionLowersRecallOnly() {
        EvaluationCase testCase = firstCase();

        EvaluationResult result = new ExtractionEvaluator(
                fixedExtractor(List.of(), List.of(), List.of())).evaluate(testCase);

        assertThat(result.recall()).isZero();
        // No propuso nada incorrecto: la precisión no se ve afectada.
        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.observations()).anySatisfy(
                o -> assertThat(o).contains("omitida"));
    }

    @Test
    @DisplayName("proponer un estado equivocado baja la precisión")
    void wrongStatusLowersPrecision() {
        EvaluationCase testCase = firstCase();

        ProgressExtractor wrong = request -> {
            StageContext structure = request.availableStages().stream()
                    .filter(s -> s.name().equalsIgnoreCase("Estructura"))
                    .findFirst().orElseThrow();
            return new AudioExtraction(
                    List.of(new StageProposal(
                            structure.stageId(), "DELAYED", "confundió el sujeto", ConfidenceLevel.HIGH)),
                    List.of(), List.of(), "test", "test", "{}");
        };

        EvaluationResult result = new ExtractionEvaluator(wrong).evaluate(testCase);

        assertThat(result.precision()).isZero();
        assertThat(result.recall()).isZero();
        assertThat(result.observations()).anySatisfy(
                o -> assertThat(o).contains("Estado incorrecto"));
    }

    @Test
    @DisplayName("referenciar una etapa prohibida se reporta como alucinación")
    void forbiddenStageIsReportedAsHallucination() {
        // Es la verificación asimétrica del conjunto: una alucinación invalida el
        // caso al margen de las métricas, porque el revisor podría aprobarla por
        // distracción.
        EvaluationCase testCase = firstCase();

        ProgressExtractor hallucinating = request -> {
            StageContext excavation = request.availableStages().stream()
                    .filter(s -> s.name().equalsIgnoreCase("Excavacion"))
                    .findFirst().orElseThrow();
            return new AudioExtraction(
                    List.of(new StageProposal(
                            excavation.stageId(), "COMPLETED", "inventado", ConfidenceLevel.HIGH)),
                    List.of(), List.of(), "test", "test", "{}");
        };

        EvaluationResult result = new ExtractionEvaluator(hallucinating).evaluate(testCase);

        assertThat(result.hasHallucinations()).isTrue();
        assertThat(result.hallucinatedStages()).contains("Excavacion");
    }

    @Test
    @DisplayName("las palabras clave ausentes se reportan")
    void missingKeywordsAreReported() {
        EvaluationCase testCase = firstCase();

        EvaluationResult result = new ExtractionEvaluator(
                fixedExtractor(List.of(), List.of(), List.of())).evaluate(testCase);

        // Sin texto no puede haber términos corregidos por contexto.
        assertThat(result.missingKeywords())
                .containsExactlyInAnyOrder("revoque", "corrugado", "sanitario");
    }

    @Test
    @DisplayName("las novedades y alertas insuficientes se reportan")
    void insufficientNotesAreReported() {
        EvaluationCase testCase = firstCase();

        EvaluationResult result = new ExtractionEvaluator(
                fixedExtractor(List.of(), List.of(), List.of())).evaluate(testCase);

        assertThat(result.observations()).anySatisfy(o -> assertThat(o).contains("novedades"));
        assertThat(result.observations()).anySatisfy(o -> assertThat(o).contains("alertas"));
    }

    @Test
    @DisplayName("el adaptador simulado corre el conjunto sin errores")
    void mockExtractorRunsTheCaseSet() {
        // No se le exige calidad: sus reglas son rudimentarias a propósito. Lo que
        // se verifica es que el motor corra de punta a punta sin proveedor real.
        ExtractionEvaluator evaluator = new ExtractionEvaluator(new MockProgressExtractor());

        for (EvaluationCase testCase : ExtractionEvaluator.loadCases().cases()) {
            EvaluationResult result = evaluator.evaluate(testCase);
            assertThat(result.caseId()).isEqualTo(testCase.id());
            assertThat(result.precision()).isBetween(0.0, 1.0);
            assertThat(result.recall()).isBetween(0.0, 1.0);
        }
    }
}