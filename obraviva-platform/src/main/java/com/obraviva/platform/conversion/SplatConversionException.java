package com.obraviva.platform.conversion;

/**
 * Falla técnica de la conversión de formato: herramienta ausente, timeout,
 * salida corrupta. El trabajo es candidato a reintento.
 *
 * <p>Mismo criterio que {@code VideoProcessingException} frente a
 * {@code VideoValidationException}: separar lo que se arregla reintentando de lo
 * que no.
 */
public class SplatConversionException extends RuntimeException {

    public SplatConversionException(String message) {
        super(message);
    }

    public SplatConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}