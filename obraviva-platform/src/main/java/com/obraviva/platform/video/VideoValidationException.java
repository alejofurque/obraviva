package com.obraviva.platform.video;

/**
 * El video subido no sirve para generar una reconstrucción y hay que rechazarlo.
 * El mensaje es apto para mostrarle al usuario tal cual: quien filma es el
 * encargado de obra, no un técnico.
 */
public class VideoValidationException extends RuntimeException {

    public enum Reason {
        NO_VIDEO_STREAM,
        TOO_SHORT,
        UNREADABLE
    }

    private final Reason reason;

    public VideoValidationException(Reason reason, String userMessage) {
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