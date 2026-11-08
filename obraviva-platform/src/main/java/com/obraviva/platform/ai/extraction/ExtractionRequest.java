package com.obraviva.platform.ai.extraction;

import java.util.List;

/**
 * Entrada de la interpretación: la transcripción y las etapas que existen en el
 * desarrollo.
 *
 * <p>Las etapas viajan con la petición porque el modelo no consulta la base: recibe
 * el universo de opciones válidas en el propio prompt.
 */
public record ExtractionRequest(
        String transcript,
        List<StageContext> availableStages) {

    public ExtractionRequest {
        availableStages = availableStages == null ? List.of() : List.copyOf(availableStages);
    }
}