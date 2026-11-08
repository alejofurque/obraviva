package com.obraviva.platform.ai.evaluation;

import java.util.List;

/**
 * Resultado de evaluar un caso.
 *
 * <p>Las métricas se calculan sobre las propuestas de cambio de estado, que son lo
 * único comparable de forma exacta. Las novedades y alertas se cuentan y su
 * contenido se conserva para inspección manual.
 */
public record EvaluationResult(
        String caseId,
        int expectedProposals,
        int correctProposals,
        int incorrectProposals,
        int progressNotes,
        int supplyAlerts,
        List<String> hallucinatedStages,
        List<String> missingKeywords,
        List<String> observations) {

    /**
     * Precisión: de lo que el modelo propuso, cuánto era correcto. Penaliza
     * inventar.
     */
    public double precision() {
        int total = correctProposals + incorrectProposals;
        return total == 0 ? 1.0 : (double) correctProposals / total;
    }

    /**
     * Exhaustividad: de lo que había que detectar, cuánto detectó. Penaliza omitir.
     */
    public double recall() {
        return expectedProposals == 0 ? 1.0 : (double) correctProposals / expectedProposals;
    }

    /** Media armónica de ambas, para tener un número comparable entre corridas. */
    public double f1() {
        double p = precision();
        double r = recall();
        return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
    }

    /**
     * Una alucinación de etapa invalida el caso, sin importar las métricas. Es la
     * asimetría que el conjunto declara explícitamente.
     */
    public boolean hasHallucinations() {
        return !hallucinatedStages.isEmpty();
    }

    public String summary() {
        return String.format(
                "%s | precisión %.2f | exhaustividad %.2f | F1 %.2f | %d novedades | %d alertas%s",
                caseId, precision(), recall(), f1(), progressNotes, supplyAlerts,
                hasHallucinations() ? " | ALUCINACIONES: " + hallucinatedStages : "");
    }
}