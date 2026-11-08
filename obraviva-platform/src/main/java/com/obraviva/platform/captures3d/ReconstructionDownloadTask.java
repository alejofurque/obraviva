package com.obraviva.platform.captures3d;

import java.util.UUID;

/** Datos para descargar el resultado del servicio externo y guardarlo en almacenamiento propio. */
public record ReconstructionDownloadTask(
        UUID jobId,
        UUID captureId,
        String externalReference,
        String resultKey) {
}