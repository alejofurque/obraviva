package com.obraviva.platform.ai.extraction;

import com.obraviva.platform.developments.StageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Depura la propuesta del modelo antes de que llegue a manos del revisor.
 *
 * <p>Es la pieza que hace cumplir el principio rector del documento maestro. Un
 * modelo de lenguaje puede devolver identificadores inventados, estados que no
 * existen o descripciones vacías; nada de eso debe alcanzar la pantalla de
 * confirmación, porque el revisor no tendría cómo reconocerlo ni contrastarlo.
 *
 * <p>El criterio es descartar en silencio y dejar constancia en el registro, no
 * intentar corregir: adivinar a qué etapa se refería un identificador inexistente
 * sería inventar sobre lo inventado.
 *
 * <p>No es una clase de infraestructura ni depende del proveedor: se aplica igual a
 * la salida del adaptador simulado que a la del real.
 */
public class ExtractionValidator {

    private static final Logger log = LoggerFactory.getLogger(ExtractionValidator.class);

    /** Longitud máxima de una descripción propuesta, para evitar textos desbordados. */
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    public AudioExtraction validate(AudioExtraction extraction, List<StageContext> availableStages) {
        Set<UUID> validStageIds = availableStages == null
                ? Set.of()
                : availableStages.stream().map(StageContext::stageId).collect(Collectors.toSet());

        List<StageProposal> stages = new ArrayList<>();
        for (StageProposal proposal : extraction.stageProposals()) {
            if (proposal.stageId() == null || !validStageIds.contains(proposal.stageId())) {
                log.warn("Se descarta una propuesta que referencia una etapa inexistente: {}",
                        proposal.stageId());
                continue;
            }
            if (!isValidStatus(proposal.proposedStatus())) {
                log.warn("Se descarta una propuesta con estado inválido '{}' para la etapa {}",
                        proposal.proposedStatus(), proposal.stageId());
                continue;
            }
            stages.add(new StageProposal(
                    proposal.stageId(),
                    proposal.proposedStatus().trim().toUpperCase(),
                    truncate(proposal.rationale()),
                    proposal.confidence() == null ? ConfidenceLevel.LOW : proposal.confidence()));
        }

        List<ProgressNote> notes = new ArrayList<>();
        for (ProgressNote note : extraction.progressNotes()) {
            if (isBlank(note.description())) {
                log.warn("Se descarta una novedad sin descripción");
                continue;
            }
            // Una etapa inexistente no invalida la novedad: se conserva sin etapa,
            // porque el modelo de datos admite avances sin etapa asociada.
            UUID stageId = (note.stageId() != null && validStageIds.contains(note.stageId()))
                    ? note.stageId()
                    : null;
            if (note.stageId() != null && stageId == null) {
                log.warn("Novedad conservada sin etapa: la referencia {} no existe", note.stageId());
            }
            notes.add(new ProgressNote(
                    stageId,
                    truncate(note.description()),
                    note.confidence() == null ? ConfidenceLevel.LOW : note.confidence()));
        }

        List<SupplyAlert> alerts = new ArrayList<>();
        for (SupplyAlert alert : extraction.supplyAlerts()) {
            if (isBlank(alert.description())) {
                log.warn("Se descarta una alerta de insumos sin descripción");
                continue;
            }
            alerts.add(new SupplyAlert(
                    truncate(alert.description()),
                    alert.confidence() == null ? ConfidenceLevel.LOW : alert.confidence()));
        }

        return new AudioExtraction(
                stages, notes, alerts,
                extraction.promptVersion(), extraction.modelId(), extraction.rawOutput());
    }

    private static boolean isValidStatus(String raw) {
        if (isBlank(raw)) {
            return false;
        }
        try {
            StageStatus.valueOf(raw.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_DESCRIPTION_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}