package com.obraviva.platform.captures3d;

import java.util.UUID;

/**
 * Datos para convertir el resultado de una reconstrucción al formato de
 * distribución, extraídos dentro de la transacción que toma el trabajo.
 *
 * <p>{@code spzKey} ya existe desde la creación de la captura: es la clave que el
 * visor consulta. Convertir es, literalmente, llenar ese lugar reservado.
 */
public record CaptureConversionTask(
        UUID jobId,
        UUID captureId,
        String resultKey,
        String spzKey) {
}