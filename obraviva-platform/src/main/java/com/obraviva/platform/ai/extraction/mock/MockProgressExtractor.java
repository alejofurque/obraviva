package com.obraviva.platform.ai.extraction.mock;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adaptador simulado de interpretación. Es el que se usa en el desarrollo diario:
 * no toca la red y no consume cuota del proveedor.
 *
 * <p>No devuelve una respuesta fija: aplica reglas de palabras clave sobre la
 * transcripción para producir una propuesta plausible y relacionada con el texto
 * real. Un mock que ignorara la entrada permitiría que el resto del pipeline
 * pareciera funcionar aunque estuviera desconectado del contenido.
 *
 * <p>Esto <strong>no</strong> es una implementación alternativa del producto: las
 * reglas son deliberadamente rudimentarias. Sirven para ejercitar la orquestación,
 * la validación y la pantalla de revisión sin depender de un servicio externo.
 */
public class MockProgressExtractor implements ProgressExtractor {

    private static final Logger log = LoggerFactory.getLogger(MockProgressExtractor.class);

    public static final String PROMPT_VERSION = "mock-v1";
    public static final String MODEL_ID = "mock";

    private static final List<String> DELAY_MARKERS =
            List.of("demor", "atras", "retras", "no lleg", "falta");
    private static final List<String> COMPLETION_MARKERS =
            List.of("terminamos", "terminado", "finaliz", "complet");
    private static final List<String> SUPPLY_MARKERS =
            List.of("compra", "material", "insumo", "proveedor", "caño", "pedido");

    @Override
    public AudioExtraction extract(ExtractionRequest request) {
        if (request == null || request.transcript() == null || request.transcript().isBlank()) {
            throw new ExtractionException("La transcripción está vacía: no hay nada que interpretar");
        }

        String text = request.transcript().toLowerCase(Locale.ROOT);
        List<StageProposal> proposals = new ArrayList<>();
        List<ProgressNote> notes = new ArrayList<>();
        List<SupplyAlert> alerts = new ArrayList<>();

        for (StageContext stage : request.availableStages()) {
            if (!mentions(text, stage.name())) {
                continue;
            }
            String status = resolveStatus(text);
            proposals.add(new StageProposal(
                    stage.stageId(),
                    status,
                    "El audio menciona '" + stage.name() + "'",
                    ConfidenceLevel.MEDIUM));
            notes.add(new ProgressNote(
                    stage.stageId(),
                    "Novedad simulada sobre " + stage.name(),
                    ConfidenceLevel.MEDIUM));
        }

        if (containsAny(text, SUPPLY_MARKERS)) {
            alerts.add(new SupplyAlert(
                    "El audio menciona materiales o compras pendientes",
                    ConfidenceLevel.MEDIUM));
        }

        AudioExtraction extraction = new AudioExtraction(
                proposals, notes, alerts, PROMPT_VERSION, MODEL_ID,
                "{\"simulado\":true}");

        log.info("[MOCK] Interpretación simulada: {} elementos propuestos",
                extraction.elementCount());
        return extraction;
    }

    /**
     * Coincidencia laxa: alcanza con que alguna palabra significativa del nombre de
     * la etapa aparezca en el texto. La transcripción automática rara vez reproduce
     * el nombre completo tal como está en la base.
     */
    private static boolean mentions(String text, String stageName) {
        if (stageName == null || stageName.isBlank()) {
            return false;
        }
        for (String word : stageName.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.length() >= 5 && text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveStatus(String text) {
        if (containsAny(text, DELAY_MARKERS)) {
            return "DELAYED";
        }
        if (containsAny(text, COMPLETION_MARKERS)) {
            return "COMPLETED";
        }
        return "IN_PROGRESS";
    }

    private static boolean containsAny(String text, List<String> markers) {
        return markers.stream().anyMatch(text::contains);
    }
}