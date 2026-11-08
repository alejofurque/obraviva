package com.obraviva.platform.captures3d;

import java.util.UUID;

/**
 * Datos mínimos que el worker necesita para procesar un video, extraídos dentro de
 * la transacción que toma el trabajo. Se pasan como record y no como entidades para
 * que el trabajo pesado corra sin sesión de persistencia abierta: un transcodificado
 * puede tardar minutos y no debe retener una conexión de base.
 */
public record VideoPreprocessingTask(
        UUID jobId,
        UUID captureId,
        String sourceKey,
        String targetKey) {
}