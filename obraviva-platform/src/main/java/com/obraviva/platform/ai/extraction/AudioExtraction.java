package com.obraviva.platform.ai.extraction;

import java.util.List;

/**
 * Propuesta estructurada obtenida de un audio, antes de cualquier confirmación
 * humana. Nada de esto se publica: es lo que el responsable revisa, corrige y
 * aprueba en la pantalla de confirmación.
 *
 * <p>{@code rawOutput}, {@code promptVersion} y {@code modelId} se conservan por
 * trazabilidad, según pide el documento maestro: ante una propuesta discutible hay
 * que poder reconstruir con qué versión de prompt y de modelo se generó.
 */
public record AudioExtraction(
        List<StageProposal> stageProposals,
        List<ProgressNote> progressNotes,
        List<SupplyAlert> supplyAlerts,
        String promptVersion,
        String modelId,
        String rawOutput) {

    public AudioExtraction {
        stageProposals = stageProposals == null ? List.of() : List.copyOf(stageProposals);
        progressNotes = progressNotes == null ? List.of() : List.copyOf(progressNotes);
        supplyAlerts = supplyAlerts == null ? List.of() : List.copyOf(supplyAlerts);
    }

    public boolean isEmpty() {
        return stageProposals.isEmpty() && progressNotes.isEmpty() && supplyAlerts.isEmpty();
    }

    public int elementCount() {
        return stageProposals.size() + progressNotes.size() + supplyAlerts.size();
    }
}