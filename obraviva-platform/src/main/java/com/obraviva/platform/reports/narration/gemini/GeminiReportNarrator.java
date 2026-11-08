package com.obraviva.platform.reports.narration.gemini;

import com.obraviva.platform.ai.ChatCompletion;
import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.ReportDataCodec;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
import com.obraviva.platform.reports.narration.ReportNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador real de redacción contra un modelo de lenguaje.
 *
 * <p>Pese al nombre, no depende de Gemini: recibe un {@link ChatCompletion} y no
 * conoce al proveedor. El nombre refleja el modelo con el que se calibró el prompt,
 * porque la redacción de un prompt sí es específica de la familia de modelos.
 *
 * <p>A diferencia del adaptador de interpretación, acá la salida es prosa y no JSON,
 * así que no hay análisis estructural que hacer. Lo único que se limpia es el bloque
 * de código con el que algunos modelos envuelven su respuesta aunque el prompt lo
 * prohíba: eso es un artefacto de formato, no contenido.
 *
 * <p>La verificación de que el texto no contenga cifras inventadas NO ocurre acá:
 * vive en {@code NarrativeGuard} y se aplica en el worker, del mismo modo que la
 * validación de las propuestas de audio vive fuera de su adaptador. Así también el
 * adaptador simulado queda sujeto a la misma comprobación.
 */
public class GeminiReportNarrator implements ReportNarrator {

    private static final Logger log = LoggerFactory.getLogger(GeminiReportNarrator.class);

    private static final String DATA_PLACEHOLDER = "{{DATOS}}";

    private final ChatCompletion chatCompletion;
    private final ReportDataCodec codec;
    private final String promptTemplate;
    private final String promptVersion;
    private final String modelId;

    public GeminiReportNarrator(
            ChatCompletion chatCompletion,
            ReportDataCodec codec,
            String promptTemplate,
            String promptVersion,
            String modelId) {
        this.chatCompletion = chatCompletion;
        this.codec = codec;
        this.promptTemplate = promptTemplate;
        this.promptVersion = promptVersion;
        this.modelId = modelId;
    }

    @Override
    public Narration narrate(ReportData data) {
        if (data == null) {
            throw new NarrationException("No hay snapshot que redactar");
        }

        String prompt = promptTemplate.replace(DATA_PLACEHOLDER, codec.encode(data));

        String raw;
        try {
            raw = chatCompletion.complete(prompt);
        } catch (RuntimeException e) {
            throw new NarrationException(
                    "Error consultando al modelo de lenguaje: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new NarrationException("El modelo de lenguaje devolvió una respuesta vacía");
        }

        String text = stripCodeFence(raw);
        if (text.isBlank()) {
            throw new NarrationException(
                    "El modelo devolvió un bloque sin texto utilizable", raw, null);
        }

        log.info("Reporte redactado por {} (prompt {}): {} caracteres",
                modelId, promptVersion, text.length());

        // La respuesta cruda se conserva completa, con el envoltorio incluido: es lo
        // que permite después distinguir un problema de formato de uno de contenido.
        return new Narration(text, promptVersion, modelId, raw);
    }

    /**
     * Quita el bloque de código con el que algunos modelos envuelven la salida pese a
     * que el prompt lo prohíbe. Se acota a ese caso: cualquier otro recorte del texto
     * sería editar el contenido, y eso le corresponde al revisor.
     */
    private static String stripCodeFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return "";
        }
        String body = text.substring(firstBreak + 1);
        int closing = body.lastIndexOf("```");
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }
}