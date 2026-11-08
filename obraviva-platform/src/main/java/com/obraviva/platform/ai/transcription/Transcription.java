package com.obraviva.platform.ai.transcription;

import java.time.Duration;

/**
 * Resultado de la transcripción: el texto reconocido y cuánto costó obtenerlo.
 *
 * <p>El tiempo de proceso se conserva porque es un dato útil para el registro del
 * trabajo y para dimensionar la cola.
 */
public record Transcription(
        String text,
        Duration audioDuration,
        Duration processingTime) {

    public int characterCount() {
        return text == null ? 0 : text.length();
    }

    public String summary() {
        return String.format(
                "Audio de %.1f s transcripto en %.1f s (%d caracteres)",
                audioDuration.toMillis() / 1000.0,
                processingTime.toMillis() / 1000.0,
                characterCount());
    }
}