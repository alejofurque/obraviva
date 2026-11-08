package com.obraviva.platform.ai.extraction;

import java.util.UUID;

/**
 * Cambio de estado propuesto para una etapa existente.
 *
 * <p>{@code proposedStatus} se declara como texto y no como el enum del dominio:
 * lo que devuelve el modelo es una cadena que puede no corresponder a ningún valor
 * válido. La conversión ocurre en la validación, que descarta lo que no encaje.
 */
public record StageProposal(
        UUID stageId,
        String proposedStatus,
        /** Fragmento del audio que sustenta la propuesta, para que el revisor lo contraste. */
        String rationale,
        ConfidenceLevel confidence) {
}