package com.obraviva.platform.ai.evaluation;

import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.ExtractionValidator;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ProgressNote;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.ai.extraction.StageProposal;
import com.obraviva.platform.ai.extraction.SupplyAlert;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Motor del conjunto de evaluación: corre cada caso contra un extractor y calcula
 * las métricas.
 *
 * <p>Vive en el árbol de tests porque no es código de producción: es
 * instrumentación de medición. Funciona con cualquier implementación de
 * {@link ProgressExtractor}, de modo que se puede evaluar el adaptador simulado sin
 * costo o el real contra el proveedor.
 *
 * <p>Aplica siempre {@link ExtractionValidator} antes de medir, porque lo que
 * importa es la calidad de lo que llegaría al revisor, no la de la respuesta cruda
 * del modelo.
 */
public class ExtractionEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CASES_RESOURCE = "evaluation/extraction-cases.json";

    private final ProgressExtractor extractor;
    private final ExtractionValidator validator = new ExtractionValidator();

    public ExtractionEvaluator(ProgressExtractor extractor) {
        this.extractor = extractor;
    }

    /** Conjunto de casos declarado en el archivo de recursos. */
    public record CaseSet(String description, List<EvaluationCase> cases) {
    }

    public static CaseSet loadCases() {
        try (InputStream in = ExtractionEvaluator.class.getClassLoader()
                .getResourceAsStream(CASES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "No se encontró el conjunto de evaluación en " + CASES_RESOURCE);
            }
            return JSON.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CaseSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el conjunto de evaluación", e);
        }
    }

    public EvaluationResult evaluate(EvaluationCase testCase) {
        // Los identificadores se generan acá: el conjunto declara etapas por nombre
        // para poder correrse en cualquier entorno, sin depender de una base concreta.
        Map<String, UUID> idsByName = new HashMap<>();
        Map<UUID, String> namesById = new HashMap<>();
        List<StageContext> stages = new ArrayList<>();

        for (EvaluationCase.ExpectedStage stage : testCase.stages()) {
            UUID id = UUID.randomUUID();
            idsByName.put(normalize(stage.name()), id);
            namesById.put(id, stage.name());
            stages.add(new StageContext(id, stage.name(), stage.orderIndex(), stage.currentStatus()));
        }

        AudioExtraction raw = extractor.extract(
                new ExtractionRequest(testCase.transcript(), stages));
        AudioExtraction result = validator.validate(raw, stages);

        return score(testCase, result, idsByName, namesById);
    }

    private EvaluationResult score(
            EvaluationCase testCase,
            AudioExtraction result,
            Map<String, UUID> idsByName,
            Map<UUID, String> namesById) {

        EvaluationCase.Expectations expected = testCase.expected();
        List<String> observations = new ArrayList<>();

        // --- Propuestas de estado: comparación exacta ---

        Map<UUID, String> expectedByStage = new HashMap<>();
        for (EvaluationCase.ExpectedProposal proposal : expected.stageProposals()) {
            UUID id = idsByName.get(normalize(proposal.stageName()));
            if (id == null) {
                throw new IllegalStateException(
                        "El caso " + testCase.id() + " espera una propuesta para la etapa '"
                                + proposal.stageName() + "', que no está declarada en el caso");
            }
            expectedByStage.put(id, proposal.proposedStatus().toUpperCase(Locale.ROOT));
        }

        int correct = 0;
        int incorrect = 0;
        for (StageProposal proposal : result.stageProposals()) {
            String expectedStatus = expectedByStage.get(proposal.stageId());
            String stageName = namesById.getOrDefault(proposal.stageId(), "(desconocida)");

            if (expectedStatus == null) {
                incorrect++;
                observations.add("Propuesta no esperada para '" + stageName
                        + "': " + proposal.proposedStatus());
            } else if (expectedStatus.equals(proposal.proposedStatus().toUpperCase(Locale.ROOT))) {
                correct++;
            } else {
                incorrect++;
                observations.add("Estado incorrecto para '" + stageName + "': se esperaba "
                        + expectedStatus + " y se obtuvo " + proposal.proposedStatus());
            }
        }

        for (Map.Entry<UUID, String> entry : expectedByStage.entrySet()) {
            boolean found = result.stageProposals().stream()
                    .anyMatch(p -> entry.getKey().equals(p.stageId()));
            if (!found) {
                observations.add("Propuesta omitida para '"
                        + namesById.get(entry.getKey()) + "': se esperaba " + entry.getValue());
            }
        }

        // --- Alucinaciones: verificación aparte ---

        List<String> hallucinated = new ArrayList<>();
        for (String forbidden : expected.forbiddenStages()) {
            UUID forbiddenId = idsByName.get(normalize(forbidden));
            if (forbiddenId == null) {
                continue;
            }
            boolean referenced = result.stageProposals().stream()
                    .anyMatch(p -> forbiddenId.equals(p.stageId()));
            if (referenced) {
                hallucinated.add(forbidden);
            }
        }

        // --- Novedades y alertas: recuento y palabras clave ---

        if (result.progressNotes().size() < expected.minProgressNotes()) {
            observations.add("Se esperaban al menos " + expected.minProgressNotes()
                    + " novedades y se obtuvieron " + result.progressNotes().size());
        }
        if (result.supplyAlerts().size() < expected.minSupplyAlerts()) {
            observations.add("Se esperaban al menos " + expected.minSupplyAlerts()
                    + " alertas de insumos y se obtuvieron " + result.supplyAlerts().size());
        }

        String allText = collectText(result);
        List<String> missingKeywords = new ArrayList<>();
        for (String keyword : expected.expectedKeywords()) {
            if (!allText.contains(normalize(keyword))) {
                missingKeywords.add(keyword);
            }
        }

        return new EvaluationResult(
                testCase.id(),
                expectedByStage.size(),
                correct,
                incorrect,
                result.progressNotes().size(),
                result.supplyAlerts().size(),
                hallucinated,
                missingKeywords,
                observations);
    }

    /**
     * Junta todo el texto libre de la propuesta para buscar palabras clave. Las
     * descripciones no se comparan literalmente: sólo se verifica que ciertos
     * términos aparezcan, que es lo que se puede afirmar con rigor sobre texto libre.
     */
    private static String collectText(AudioExtraction result) {
        StringBuilder text = new StringBuilder();
        for (ProgressNote note : result.progressNotes()) {
            text.append(' ').append(note.description());
        }
        for (SupplyAlert alert : result.supplyAlerts()) {
            text.append(' ').append(alert.description());
        }
        for (StageProposal proposal : result.stageProposals()) {
            text.append(' ').append(proposal.rationale());
        }
        return normalize(text.toString());
    }

    /** Normaliza para comparar: minúsculas y sin acentos. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}