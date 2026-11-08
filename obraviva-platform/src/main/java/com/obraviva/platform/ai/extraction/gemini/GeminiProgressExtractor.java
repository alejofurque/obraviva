package com.obraviva.platform.ai.extraction.gemini;

import com.obraviva.platform.ai.ChatCompletion;
import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ConfidenceLevel;
import com.obraviva.platform.ai.extraction.ExtractionException;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ProgressNote;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.ai.extraction.StageProposal;
import com.obraviva.platform.ai.extraction.SupplyAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador real de interpretación contra un modelo de lenguaje.
 *
 * <p>Pese al nombre, no depende de Gemini: recibe un {@link ChatCompletion} y no
 * conoce al proveedor. El nombre refleja el modelo con el que se calibró el prompt,
 * porque la redacción de un prompt sí es específica de la familia de modelos.
 *
 * <p><strong>La respuesta cruda se conserva siempre</strong>, incluso cuando el
 * análisis falla parcialmente: el documento maestro exige poder reconstruir qué
 * devolvió el modelo ante una propuesta discutible.
 *
 * <p>El análisis es deliberadamente tolerante. Un modelo de lenguaje puede envolver
 * el JSON en un bloque de código, agregar texto antes o después, u omitir campos.
 * Nada de eso justifica descartar toda la respuesta: se extrae lo interpretable y
 * {@link com.obraviva.platform.ai.extraction.ExtractionValidator} depura después
 * contra el dominio.
 */
public class GeminiProgressExtractor implements ProgressExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiProgressExtractor.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String STAGES_PLACEHOLDER = "{{ETAPAS}}";
    private static final String TRANSCRIPT_PLACEHOLDER = "{{TRANSCRIPCION}}";

    private final ChatCompletion chatCompletion;
    private final String promptTemplate;
    private final String promptVersion;
    private final String modelId;

    public GeminiProgressExtractor(
            ChatCompletion chatCompletion,
            String promptTemplate,
            String promptVersion,
            String modelId) {
        this.chatCompletion = chatCompletion;
        this.promptTemplate = promptTemplate;
        this.promptVersion = promptVersion;
        this.modelId = modelId;
    }

    @Override
    public AudioExtraction extract(ExtractionRequest request) {
        if (request == null || request.transcript() == null || request.transcript().isBlank()) {
            throw new ExtractionException("La transcripción está vacía: no hay nada que interpretar");
        }
        if (request.availableStages().isEmpty()) {
            log.warn("El desarrollo no tiene etapas cargadas: no se podrán proponer cambios de estado");
        }

        String prompt = buildPrompt(request);
        String raw;
        try {
            raw = chatCompletion.complete(prompt);
        } catch (RuntimeException e) {
            throw new ExtractionException(
                    "Error consultando al modelo de lenguaje: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new ExtractionException("El modelo de lenguaje devolvió una respuesta vacía");
        }

        return parse(raw);
    }

    private String buildPrompt(ExtractionRequest request) {
        StringBuilder stages = new StringBuilder();
        for (StageContext stage : request.availableStages()) {
            stages.append("- id: ").append(stage.stageId())
                    .append(" | nombre: ").append(stage.name())
                    .append(" | orden: ").append(stage.orderIndex())
                    .append(" | estado actual: ").append(stage.currentStatus())
                    .append(System.lineSeparator());
        }
        if (stages.isEmpty()) {
            stages.append("(el desarrollo no tiene etapas cargadas)");
        }

        return promptTemplate
                .replace(STAGES_PLACEHOLDER, stages.toString().trim())
                .replace(TRANSCRIPT_PLACEHOLDER, request.transcript().trim());
    }

    private AudioExtraction parse(String raw) {
        JsonNode root;
        try {
            root = JSON.readTree(stripCodeFence(raw));
        } catch (RuntimeException e) {
            // La respuesta viaja dentro de la excepción: es el único momento en que
            // hace falta y, si no se transporta acá, se pierde.
            log.warn("El modelo devolvió una respuesta que no es JSON válido ({} caracteres): {}",
                    raw.length(), abbreviate(raw));
            throw new ExtractionException(describeParseFailure(raw), raw, e);
        }

        List<StageProposal> proposals = new ArrayList<>();
        for (JsonNode node : arrayAt(root, "stageProposals")) {
            proposals.add(new StageProposal(
                    parseUuid(text(node, "stageId")),
                    text(node, "proposedStatus"),
                    text(node, "rationale"),
                    ConfidenceLevel.fromText(text(node, "confidence"))));
        }

        List<ProgressNote> notes = new ArrayList<>();
        for (JsonNode node : arrayAt(root, "progressNotes")) {
            notes.add(new ProgressNote(
                    parseUuid(text(node, "stageId")),
                    text(node, "description"),
                    ConfidenceLevel.fromText(text(node, "confidence"))));
        }

        List<SupplyAlert> alerts = new ArrayList<>();
        for (JsonNode node : arrayAt(root, "supplyAlerts")) {
            alerts.add(new SupplyAlert(
                    text(node, "description"),
                    ConfidenceLevel.fromText(text(node, "confidence"))));
        }

        AudioExtraction extraction = new AudioExtraction(
                proposals, notes, alerts, promptVersion, modelId, raw);
        log.info("Interpretación completada: {} elementos propuestos por {} (prompt {})",
                extraction.elementCount(), modelId, promptVersion);
        return extraction;
    }

    /**
     * Distingue una respuesta truncada de una malformada. La diferencia importa: la
     * primera se resuelve subiendo el tope de tokens y la segunda ajustando el
     * prompt, y confundirlas cuesta horas.
     */
    private static String describeParseFailure(String raw) {
        String text = raw.trim();
        int opening = text.indexOf('{');
        int closing = text.lastIndexOf('}');

        if (opening >= 0 && closing < opening) {
            return "El modelo devolvió una respuesta incompleta: el JSON quedó sin cerrar, "
                    + "probablemente por agotarse el tope de tokens de salida";
        }
        if (opening < 0) {
            return "El modelo no devolvió ningún objeto JSON";
        }
        return "El modelo de lenguaje no devolvió un JSON interpretable";
    }

    /**
     * Quita el bloque de código con el que los modelos suelen envolver el JSON pese
     * a que el prompt lo prohíbe, y descarta cualquier texto anterior o posterior
     * quedándose con el objeto más externo.
     */
    private static String stripCodeFence(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static Iterable<JsonNode> arrayAt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node == null || !node.isArray()) ? List.of() : node;
    }

    /**
     * Los valores se leen como texto y se convierten a mano. Jackson 3 renombró
     * {@code asText()} a {@code asString()}, y depender de un solo accesor cuya
     * existencia está confirmada es preferible a suponer los demás.
     */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asString();
        return (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim()))
                ? null
                : raw.trim();
    }

    /**
     * Un identificador mal formado no aborta la interpretación: se devuelve nulo y
     * el validador descarta el elemento contra el dominio, que es donde vive esa
     * decisión.
     */
    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("El modelo devolvió un identificador de etapa mal formado: {}", raw);
            return null;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(sin respuesta)";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}