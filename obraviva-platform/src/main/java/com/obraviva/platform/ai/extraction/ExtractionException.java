package com.obraviva.platform.ai.extraction;

/**
 * Falla técnica de la interpretación: proveedor caído, cuota agotada, respuesta
 * ininteligible. El trabajo es candidato a reintento.
 *
 * <p>Cuando el fallo ocurre analizando una respuesta, ésta se transporta en la
 * excepción. Sin eso, el único momento en que la respuesta cruda importa de verdad
 * —cuando no se pudo interpretar— es justamente aquel en el que se pierde. Ya
 * ocurrió: una respuesta truncada por el tope de tokens sólo pudo diagnosticarse
 * rastreando el registro a mano.
 */
public class ExtractionException extends RuntimeException {

    private final String rawOutput;

    public ExtractionException(String message) {
        this(message, null, null);
    }

    public ExtractionException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public ExtractionException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    /** Respuesta cruda del modelo, si el fallo ocurrió al analizarla. */
    public String rawOutput() {
        return rawOutput;
    }

    public boolean hasRawOutput() {
        return rawOutput != null && !rawOutput.isBlank();
    }
}