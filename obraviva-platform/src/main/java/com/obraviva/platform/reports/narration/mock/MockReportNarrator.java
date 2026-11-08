package com.obraviva.platform.reports.narration.mock;

import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
import com.obraviva.platform.reports.narration.ReportNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adaptador simulado de redacción. Es el que se usa en el desarrollo diario: no toca
 * la red y no consume cuota del proveedor.
 *
 * <p>No devuelve un texto fijo: compone la prosa a partir del snapshot recibido, con
 * plantillas rudimentarias. Un mock que ignorara la entrada permitiría que la pantalla
 * de revisión pareciera funcionar aunque estuviera desconectada de los datos.
 *
 * <p>Toda cifra del texto sale de {@code totals}, calculado por el motor de datos.
 * Esto <strong>no</strong> es una implementación alternativa del producto: la prosa es
 * pobre a propósito. Sirve para ejercitar la orquestación y la revisión sin depender
 * de un servicio externo.
 */
public class MockReportNarrator implements ReportNarrator {

    private static final Logger log = LoggerFactory.getLogger(MockReportNarrator.class);

    public static final String PROMPT_VERSION = "mock-v1";
    public static final String MODEL_ID = "mock";

    private static final int MAX_LISTED_ITEMS = 10;

    @Override
    public Narration narrate(ReportData data) {
        if (data == null) {
            throw new NarrationException("No hay snapshot que redactar");
        }

        StringBuilder text = new StringBuilder();

        text.append("Entre el ").append(data.periodStart())
                .append(" y el ").append(data.periodEnd())
                .append(", la obra ").append(data.developmentName())
                .append(" registró ").append(data.totals().progressEntries())
                .append(plural(data.totals().progressEntries(), " avance publicado", " avances publicados"))
                .append(" y ").append(data.totals().captures())
                .append(plural(data.totals().captures(), " recorrido 3D nuevo", " recorridos 3D nuevos"))
                .append(".");

        appendStages(text, data);
        appendEntries(text, data);
        appendCaptures(text, data);

        log.info("[MOCK] Reporte redactado de forma simulada: {} caracteres", text.length());

        return new Narration(
                text.toString(), PROMPT_VERSION, MODEL_ID, "{\"simulado\":true}");
    }

    private static void appendStages(StringBuilder text, ReportData data) {
        if (data.stages().isEmpty()) {
            return;
        }
        text.append("\n\nAl momento de generarse este reporte, la obra tiene ")
                .append(data.stages().size())
                .append(plural(data.stages().size(), " etapa cargada", " etapas cargadas"))
                .append(": ").append(data.totals().stagesCompleted()).append(" completadas, ")
                .append(data.totals().stagesInProgress()).append(" en ejecución, ")
                .append(data.totals().stagesDelayed()).append(" demoradas y ")
                .append(data.totals().stagesPending()).append(" pendientes.");

        if (data.totals().stagesDelayed() > 0) {
            // Las demoras se nombran, no se esconden: es una decisión de producto del
            // documento maestro y el texto tiene que reflejarla.
            text.append(" Las etapas demoradas se detallan más abajo.");
        }
    }

    private static void appendEntries(StringBuilder text, ReportData data) {
        if (data.progressEntries().isEmpty()) {
            return;
        }
        text.append("\n\nAvances del período:");
        for (ReportData.ProgressItem entry : limit(data.progressEntries())) {
            text.append("\n- ").append(entry.date()).append(": ").append(entry.description());
            if (entry.stageName() != null) {
                text.append(" (etapa: ").append(entry.stageName()).append(")");
            }
        }
        appendOmitted(text, data.progressEntries().size());
    }

    private static void appendCaptures(StringBuilder text, ReportData data) {
        if (data.captures().isEmpty()) {
            return;
        }
        text.append("\n\nRecorridos 3D publicados:");
        for (ReportData.CaptureItem capture : limit(data.captures())) {
            text.append("\n- ").append(capture.date());
            if (capture.description() != null) {
                text.append(": ").append(capture.description());
            }
        }
        appendOmitted(text, data.captures().size());
    }

    private static <T> List<T> limit(List<T> items) {
        return items.size() <= MAX_LISTED_ITEMS ? items : items.subList(0, MAX_LISTED_ITEMS);
    }

    private static void appendOmitted(StringBuilder text, int total) {
        if (total > MAX_LISTED_ITEMS) {
            text.append("\n- (").append(total - MAX_LISTED_ITEMS).append(" más)");
        }
    }

    private static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }
}