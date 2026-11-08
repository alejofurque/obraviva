package com.obraviva.platform.reports.narration;

/**
 * Resultado de la redacción de un reporte.
 *
 * <p>Viaja con su procedencia: versión de prompt, identificador de modelo y respuesta
 * cruda. Ante un texto discutible hay que poder reconstruir con qué se generó, mismo
 * criterio que en las interpretaciones de audio.
 */
public record Narration(
        String text,
        String promptVersion,
        String modelId,
        String rawOutput) {
}