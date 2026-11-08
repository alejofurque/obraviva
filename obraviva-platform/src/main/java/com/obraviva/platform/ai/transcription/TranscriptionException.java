package com.obraviva.platform.ai.transcription;

/**
 * Falla técnica de la transcripción: herramienta ausente, modelo no encontrado,
 * timeout. El trabajo es candidato a reintento.
 *
 * <p>Mismo criterio que en los módulos de video y conversión: separar lo que se
 * arregla reintentando de lo que no.
 */
public class TranscriptionException extends RuntimeException {

    public TranscriptionException(String message) {
        super(message);
    }

    public TranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}