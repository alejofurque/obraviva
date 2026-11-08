package com.obraviva.platform.ai.transcription;

/**
 * El audio no sirve para transcribir. Reintentar con el mismo archivo daría el
 * mismo resultado.
 */
public class InvalidAudioException extends RuntimeException {

    public enum Reason {
        /** El archivo no tiene pista de audio o no es legible. */
        UNREADABLE,

        /** No se reconoció habla: audio en silencio, demasiado ruido o demasiado corto. */
        NO_SPEECH_DETECTED
    }

    private final Reason reason;

    public InvalidAudioException(Reason reason, String userMessage) {
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