package com.obraviva.platform.reports;

import com.obraviva.platform.jobs.PipelineStep;

import java.util.UUID;

/**
 * Datos para redactar un reporte, extraídos dentro de la transacción que toma el
 * trabajo.
 *
 * <p>El snapshot viaja ya decodificado y como record inmutable: la llamada al modelo
 * de lenguaje puede tardar decenas de segundos y no debe retener una sesión de
 * persistencia abierta.
 */
public record ReportNarrationTask(
        UUID jobId,
        UUID reportId,
        PipelineStep step,
        ReportData data) {
}