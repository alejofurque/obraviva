package com.obraviva.platform.ai.extraction;

import java.util.List;

/**
 * Las tres listas de la propuesta, sin los metadatos de trazabilidad.
 *
 * <p>Es lo único que se serializa a la columna {@code proposal}: la versión de
 * prompt, el modelo y la salida cruda tienen columnas propias porque se consultan
 * y se muestran por separado.
 */
public record ExtractionProposal(
        List<StageProposal> stageProposals,
        List<ProgressNote> progressNotes,
        List<SupplyAlert> supplyAlerts) {

    public ExtractionProposal {
        stageProposals = stageProposals == null ? List.of() : List.copyOf(stageProposals);
        progressNotes = progressNotes == null ? List.of() : List.copyOf(progressNotes);
        supplyAlerts = supplyAlerts == null ? List.of() : List.copyOf(supplyAlerts);
    }

    public static ExtractionProposal from(AudioExtraction extraction) {
        return new ExtractionProposal(
                extraction.stageProposals(),
                extraction.progressNotes(),
                extraction.supplyAlerts());
    }

    public int elementCount() {
        return stageProposals.size() + progressNotes.size() + supplyAlerts.size();
    }
}