package com.obraviva.platform.reports;

import java.util.List;

/**
 * Snapshot de los datos de un período, congelado al generarse el reporte.
 *
 * <p>Es la fuente única de la redacción posterior: el modelo de lenguaje recibe
 * exactamente esto y no puede consultar nada más. Guardarlo junto al reporte hace
 * verificable la exigencia del documento maestro de que cada afirmación del texto
 * sea trazable a un dato, y evita que reintentar la redacción cambie las cifras.
 *
 * <p><strong>Las fechas viajan como texto ISO-8601 y no como {@code LocalDate}.</strong>
 * El soporte de {@code java.time} en Jackson 3 bajo Boot 4 no está verificado en este
 * proyecto y no hay motivo para averiguarlo acá: el snapshot es un documento de texto
 * destinado a un prompt y a una pantalla, no un objeto sobre el que se calcule.
 *
 * <p>Nada de este record se calcula con un modelo de lenguaje. El motor de datos lo
 * compone; la IA sólo lo narra.
 */
public record ReportData(
        String developmentName,
        String developmentAddress,
        String periodStart,
        String periodEnd,
        List<StageState> stages,
        List<ProgressItem> progressEntries,
        List<CaptureItem> captures,
        Totals totals) {

    /**
     * Estado de una etapa.
     *
     * <p>El nombre del campo es literal a propósito: el modelo de datos no versiona
     * el estado de las etapas, así que este valor es el que la etapa tenía cuando se
     * compuso el reporte, no el que tenía al cierre del período. El prompt debe poder
     * leer esa distinción para no afirmar lo que el sistema no sabe.
     */
    public record StageState(
            String id, String name, int orderIndex, String statusAtCompositionTime) {
    }

    /** Avance vigente publicado dentro del período. */
    public record ProgressItem(
            String date, String stageName, String description, String source) {
    }

    /** Captura 3D navegable publicada dentro del período. */
    public record CaptureItem(String id, String date, String description) {
    }

    /** Conteos derivados, calculados por el motor de datos. */
    public record Totals(
            int progressEntries,
            int captures,
            int stagesCompleted,
            int stagesInProgress,
            int stagesDelayed,
            int stagesPending) {
    }

    /**
     * Un período sin avances ni capturas no da lugar a un reporte: no hay nada que
     * narrar y pedirle un texto a un modelo sobre la nada es exactamente cómo se
     * consiguen afirmaciones inventadas.
     */
    public boolean isEmpty() {
        return progressEntries.isEmpty() && captures.isEmpty();
    }
}