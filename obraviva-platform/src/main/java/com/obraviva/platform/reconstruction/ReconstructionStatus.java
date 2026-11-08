package com.obraviva.platform.reconstruction;

/**
 * Estados de una reconstrucción en el servicio externo, expresados en el vocabulario
 * de ObraViva y no en el del proveedor.
 *
 * <p>La traducción desde la representación de cada proveedor vive dentro de su
 * adaptador. Esto importa especialmente con KIRI, cuya numeración es contraintuitiva
 * (1 significa fallido y 2 exitoso) y además convive con un campo {@code code} del
 * sobre de respuesta donde 0 y 200 significan cosas distintas según la fuente.
 * Ningún entero de proveedor se filtra fuera de su adaptador.
 */
public enum ReconstructionStatus {

    /** El servicio todavía está recibiendo el archivo. */
    UPLOADING(false),

    /** Aceptado y esperando turno de cálculo. */
    QUEUED(false),

    /** Calculando. */
    PROCESSING(false),

    /** Terminado con éxito: el resultado puede descargarse. */
    SUCCEEDED(true),

    /** El cálculo falló en el servicio. El crédito ya fue consumido. */
    FAILED(true),

    /** El resultado venció y fue borrado del servicio (retención de 3 días). */
    EXPIRED(true);

    private final boolean terminal;

    ReconstructionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED;
    }
}