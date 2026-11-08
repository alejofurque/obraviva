package com.obraviva.platform.conversion;

/**
 * El archivo entregado por el servicio de reconstrucción no contiene lo que
 * esperábamos. Reintentar con el mismo archivo daría el mismo resultado.
 */
public class InvalidReconstructionArchiveException extends RuntimeException {

    public enum Reason {
        /** El archivo no es un ZIP legible. */
        NOT_AN_ARCHIVE,

        /** No hay ningún PLY adentro. */
        NO_MODEL_FOUND,

        /** Una entrada intenta escribir fuera del directorio de destino. */
        UNSAFE_ENTRY
    }

    private final Reason reason;

    public InvalidReconstructionArchiveException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}