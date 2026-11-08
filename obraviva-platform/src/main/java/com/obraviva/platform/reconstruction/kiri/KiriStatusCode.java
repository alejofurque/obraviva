package com.obraviva.platform.reconstruction.kiri;

import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionStatus;

/**
 * Estados numéricos del servicio de KIRI y su traducción al vocabulario propio.
 *
 * <p>La numeración es contraintuitiva y merece atención: <strong>1 significa fallido
 * y 2 exitoso</strong>, mientras que 0 significa "procesando" y no éxito. Para colmo,
 * el sobre de respuesta tiene su propio campo {@code code} donde 200 sí significa
 * éxito. Encapsular esto en un enum con nombres explícitos evita que una comparación
 * numérica suelta produzca un bug silencioso en el resto del sistema.
 */
public enum KiriStatusCode {

    UPLOADING(-1, ReconstructionStatus.UPLOADING),
    PROCESSING(0, ReconstructionStatus.PROCESSING),
    FAILED(1, ReconstructionStatus.FAILED),
    SUCCESSFUL(2, ReconstructionStatus.SUCCEEDED),
    QUEUING(3, ReconstructionStatus.QUEUED),
    EXPIRED(4, ReconstructionStatus.EXPIRED);

    private final int code;
    private final ReconstructionStatus status;

    KiriStatusCode(int code, ReconstructionStatus status) {
        this.code = code;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public ReconstructionStatus toDomain() {
        return status;
    }

    /**
     * Un valor desconocido es una falla técnica, no un estado más: significa que el
     * proveedor cambió su contrato y seguir adelante sería inventar un significado.
     */
    public static KiriStatusCode fromCode(int code) {
        for (KiriStatusCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new ReconstructionException(
                "El servicio de reconstrucción devolvió un estado desconocido: " + code);
    }
}