package com.obraviva.platform.video;

/**
 * Falla técnica del preprocesamiento (ffmpeg ausente, timeout, salida corrupta).
 * A diferencia de {@link VideoValidationException}, el video puede ser válido:
 * el problema es del sistema, y el trabajo es candidato a reintento.
 */
public class VideoProcessingException extends RuntimeException {

    public VideoProcessingException(String message) {
        super(message);
    }

    public VideoProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}