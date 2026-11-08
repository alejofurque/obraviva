package com.obraviva.platform.ai.extraction;

import java.util.Set;

/**
 * Estado de revisión de una interpretación de audio.
 *
 * <p>Es un ciudadano de primera clase de la arquitectura, según el documento
 * maestro: nada generado por inteligencia artificial se publica sin pasar por
 * {@link #READY_FOR_REVIEW} y ser aprobado por una persona.
 *
 * <p>Las transiciones válidas se declaran acá, mismo criterio que en
 * {@code JobStatus} y {@code PipelineStep}.
 */
public enum ReviewStatus {

    /** Recién creada: el audio se subió pero todavía no se procesó. */
    PENDING,

    /** Transcripta e interpretada: espera que una persona la revise. */
    READY_FOR_REVIEW,

    /** Aprobada por el revisor: sus propuestas se publicaron. */
    APPROVED,

    /** Descartada por el revisor: no se publica nada. */
    REJECTED,

    /** El procesamiento falló: no hay propuesta que revisar. */
    FAILED;

    public boolean canTransitionTo(ReviewStatus target) {
        return switch (this) {
            case PENDING -> Set.of(READY_FOR_REVIEW, FAILED).contains(target);
            case READY_FOR_REVIEW -> Set.of(APPROVED, REJECTED).contains(target);
            // Una vez resuelta, la interpretación no vuelve atrás: corregir un
            // avance publicado es tarea de la línea de tiempo, que ya versiona su
            // contenido.
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