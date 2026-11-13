package com.obraviva.platform.video;

import java.time.Duration;

/**
 * Límites de preprocesamiento de video. Los valores por defecto corresponden a los
 * límites documentados del servicio externo de reconstrucción 3DGS
 * (1920x1080, 3 minutos).
 */
public record VideoConstraints(
        int maxWidth,
        int maxHeight,
        int maxDurationSeconds,
        int minDurationSeconds,
        Duration probeTimeout,
        Duration transcodeTimeout
) {

    public VideoConstraints {
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("Las dimensiones máximas deben ser positivas");
        }
        if (maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("La duración máxima debe ser positiva");
        }
        if (minDurationSeconds < 0 || minDurationSeconds >= maxDurationSeconds) {
            throw new IllegalArgumentException("La duración mínima debe ser menor que la máxima");
        }
    }
}