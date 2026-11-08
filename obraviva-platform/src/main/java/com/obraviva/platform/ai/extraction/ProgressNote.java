package com.obraviva.platform.ai.extraction;

import java.util.UUID;

/**
 * Novedad narrada en el audio, candidata a convertirse en una entrada de la línea
 * de tiempo.
 *
 * <p>{@code stageId} es opcional: un avance puede no corresponder a ninguna etapa,
 * igual que en el modelo de datos.
 */
public record ProgressNote(
        UUID stageId,
        String description,
        ConfidenceLevel confidence) {
}