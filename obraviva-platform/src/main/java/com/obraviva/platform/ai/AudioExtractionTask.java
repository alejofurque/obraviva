package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.jobs.PipelineStep;

import java.util.List;
import java.util.UUID;

/**
 * Datos para procesar un paso del pipeline de audio, extraídos dentro de la
 * transacción que toma el trabajo.
 *
 * <p>Se pasan como record y no como entidades para que la transcripción y la
 * llamada al modelo de lenguaje corran sin sesión de persistencia abierta: ambas
 * pueden tardar decenas de segundos.
 */
public record AudioExtractionTask(
        UUID jobId,
        UUID extractionId,
        PipelineStep step,
        String audioKey,
        String transcript,
        List<StageContext> availableStages) {
}