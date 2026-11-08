package com.obraviva.platform.ai.evaluation;

import java.util.List;

/**
 * Caso del conjunto de evaluación, tal como se declara en el archivo de recursos.
 *
 * <p>Las etapas se declaran por nombre y no por identificador: los identificadores
 * son propios de cada base de datos y el conjunto debe poder correrse en cualquier
 * entorno. El motor genera identificadores al armar el caso y resuelve los nombres
 * contra ellos.
 */
public record EvaluationCase(
        String id,
        String notes,
        String transcript,
        List<ExpectedStage> stages,
        Expectations expected) {

    /** Etapa que existe en el desarrollo del caso. */
    public record ExpectedStage(String name, int orderIndex, String currentStatus) {
    }

    public record Expectations(
            List<ExpectedProposal> stageProposals,
            int minProgressNotes,
            int minSupplyAlerts,
            List<String> expectedKeywords,
            /**
             * Etapas que el modelo NO debe referenciar. Se verifican aparte porque
             * una alucinación pesa más que una omisión: el revisor podría aprobar
             * por distracción algo inventado, mientras que lo omitido lo agrega él.
             */
            List<String> forbiddenStages) {

        public Expectations {
            stageProposals = stageProposals == null ? List.of() : List.copyOf(stageProposals);
            expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
            forbiddenStages = forbiddenStages == null ? List.of() : List.copyOf(forbiddenStages);
        }
    }

    public record ExpectedProposal(String stageName, String proposedStatus, String rationale) {
    }
}