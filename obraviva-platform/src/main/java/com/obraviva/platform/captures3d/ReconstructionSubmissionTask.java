package com.obraviva.platform.captures3d;

import java.util.UUID;

/**
 * Datos mínimos para enviar un video al servicio de reconstrucción, extraídos dentro
 * de la transacción que toma el trabajo. Se pasan como record y no como entidades
 * para que la llamada al servicio externo corra sin sesión de persistencia abierta:
 * una subida de cien megabytes no debe retener una conexión de base.
 */
public record ReconstructionSubmissionTask(
        UUID jobId,
        UUID captureId,
        String normalizedVideoKey) {
}