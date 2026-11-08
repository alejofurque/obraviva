package com.obraviva.platform.reports;

import java.util.Set;

/**
 * Estado de un reporte de período.
 *
 * <p>Comparte los nombres con {@code ReviewStatus} del módulo de inteligencia
 * artificial, pero es un enum propio y no el mismo: son dos ciclos de vida que hoy
 * coinciden y no tienen por qué seguir haciéndolo, y compartirlo obligaría al módulo
 * de reportes a depender del de IA para algo que es suyo. La duplicación es
 * deliberada.
 *
 * <p>{@code PENDING} significa acá "compuesto, sin redactar": el snapshot ya existe
 * y el trabajo espera al modelo de lenguaje.
 */
public enum ReportStatus {

    /** Snapshot compuesto; falta la redacción. */
    PENDING,

    /** Redactado: espera que una persona lo revise. */
    READY_FOR_REVIEW,

    /** Aprobado por el revisor. */
    APPROVED,

    /** Descartado por el revisor. */
    REJECTED,

    /** La redacción falló: no hay texto que revisar. */
    FAILED;

    public boolean canTransitionTo(ReportStatus target) {
        return switch (this) {
            case PENDING -> Set.of(READY_FOR_REVIEW, FAILED).contains(target);
            case READY_FOR_REVIEW -> Set.of(APPROVED, REJECTED).contains(target);
            // Un reporte resuelto no vuelve atrás: si el período cambió, se genera
            // uno nuevo. El snapshot de éste describe un momento que ya pasó.
            case APPROVED, REJECTED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == FAILED;
    }

    public boolean isReviewable() {
        return this == READY_FOR_REVIEW;
    }
}