package com.obraviva.platform.video;

import com.obraviva.platform.video.ffmpeg.FfmpegVideoProcessor;
import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registro de los beans del módulo de video.
 *
 * <p>Se usa {@code @Value} con valores por defecto en vez de {@code @ConfigurationProperties}
 * a propósito: los defaults viven en el código, no hace falta tocar application.yml, y se
 * evita depender de la ubicación de paquetes de binding, que cambió en Spring Boot 4.
 *
 * <p>No se verifica la presencia de ffmpeg al arrancar: eso ejecutaría un proceso externo en
 * cada carga de contexto de test. La ausencia de ffmpeg se detecta al procesar un trabajo y
 * se reporta como falla técnica reintentable.
 */
@Configuration
public class VideoConfiguration {

    @Bean
    public VideoConstraints videoConstraints(
            @Value("${obraviva.video.max-width:1920}") int maxWidth,
            @Value("${obraviva.video.max-height:1080}") int maxHeight,
            @Value("${obraviva.video.max-duration-seconds:180}") int maxDurationSeconds,
            @Value("${obraviva.video.min-duration-seconds:20}") int minDurationSeconds,
            @Value("${obraviva.video.probe-timeout-seconds:60}") long probeTimeoutSeconds,
            @Value("${obraviva.video.transcode-timeout-seconds:900}") long transcodeTimeoutSeconds) {

        return new VideoConstraints(
                maxWidth,
                maxHeight,
                maxDurationSeconds,
                minDurationSeconds,
                Duration.ofSeconds(probeTimeoutSeconds),
                Duration.ofSeconds(transcodeTimeoutSeconds));
    }

    @Bean
    public ProcessRunner processRunner() {
        return new ProcessRunner();
    }

    @Bean
    public VideoProcessor videoProcessor(
            VideoConstraints videoConstraints,
            ProcessRunner processRunner,
            @Value("${obraviva.video.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${obraviva.video.ffprobe-path:ffprobe}") String ffprobePath) {

        return new FfmpegVideoProcessor(ffmpegPath, ffprobePath, videoConstraints, processRunner);
    }
}