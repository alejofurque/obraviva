package com.obraviva.platform.reports.narration;

/**
 * Falla de la redacción: proveedor caído, cuota agotada, respuesta inservible. El
 * trabajo es candidato a reintento.
 *
 * <p>A diferencia de los demás adaptadores externos del sistema, acá no hay una
 * segunda familia de "rechazo definitivo". Esa distinción existe donde el material de
 * entrada llega de afuera y puede ser irrecuperable: un video demasiado corto, un PLY
 * corrupto, un audio sin habla. La entrada de la redacción la compuso el propio motor
 * de datos y ya se verificó que no está vacía al crear el reporte, así que un snapshot
 * que el sistema no pueda narrar sería un defecto nuestro, no un dato malo.
 *
 * <p>Cuando el fallo ocurre analizando una respuesta, ésta se transporta en la
 * excepción: es el único momento en que la respuesta cruda importa de verdad, y sin
 * esto es justamente cuando se pierde.
 */
public class NarrationException extends RuntimeException {

    private final String rawOutput;

    public NarrationException(String message) {
        this(message, null, null);
    }

    public NarrationException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public NarrationException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() {
        return rawOutput;
    }

    public boolean hasRawOutput() {
        return rawOutput != null && !rawOutput.isBlank();
    }
}