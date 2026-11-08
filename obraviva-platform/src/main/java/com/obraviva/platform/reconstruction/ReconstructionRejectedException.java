package com.obraviva.platform.reconstruction;

/**
 * El servicio rechazó el trabajo de forma definitiva. Reintentar no cambia nada:
 * el material no sirve, no hay créditos, o el recurso ya no existe.
 */
public class ReconstructionRejectedException extends RuntimeException {

    public enum Reason {
        /** El video no cumple los requisitos del servicio (KIRI: código 2009). */
        VIDEO_REJECTED,

        /** Sin créditos suficientes (KIRI: HTTP 403). Reintentar no los genera. */
        INSUFFICIENT_CREDITS,

        /** La tarea no existe en el servicio (KIRI: código 2006). */
        NOT_FOUND,

        /** El resultado venció y fue borrado (retención de 3 días). */
        EXPIRED
    }

    private final Reason reason;

    public ReconstructionRejectedException(Reason reason, String userMessage) {
        super(userMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String userMessage() {
        return getMessage();
    }
}