package com.obraviva.platform.reports;

/** Falla al componer, serializar o leer un reporte de período. */
public class ReportException extends RuntimeException {

    public ReportException(String message) {
        super(message);
    }

    public ReportException(String message, Throwable cause) {
        super(message, cause);
    }
}