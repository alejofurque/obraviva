package com.obraviva.platform.reconstruction;

/**
 * Falla técnica del servicio de reconstrucción: red caída, timeout, respuesta
 * ininteligible, error 5xx del proveedor. El trabajo es candidato a reintento.
 *
 * <p>Mismo criterio que {@code VideoProcessingException} frente a
 * {@code VideoValidationException}: separar lo que se arregla reintentando de lo
 * que no.
 */
public class ReconstructionException extends RuntimeException {

    public ReconstructionException(String message) {
        super(message);
    }

    public ReconstructionException(String message, Throwable cause) {
        super(message, cause);
    }
}