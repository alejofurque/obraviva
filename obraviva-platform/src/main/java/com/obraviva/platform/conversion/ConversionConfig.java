package com.obraviva.platform.conversion;

import com.obraviva.platform.conversion.gsbox.GsboxSplatConverter;
import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registro de los beans del módulo de conversión.
 *
 * <p>No se verifica la presencia de gsbox al arrancar: eso ejecutaría un proceso
 * externo en cada carga de contexto de test. La ausencia de la herramienta se
 * detecta al procesar un trabajo y se reporta como falla técnica reintentable.
 */
@Configuration
public class ConversionConfig {

    @Bean
    public ArchiveExtractor archiveExtractor(
            @Value("${obraviva.conversion.max-extracted-mb:2048}") long maxExtractedMb) {
        return new ArchiveExtractor(maxExtractedMb * 1024L * 1024L);
    }

    @Bean
    public SplatConverter splatConverter(
            ProcessRunner processRunner,
            ArchiveExtractor archiveExtractor,
            @Value("${obraviva.conversion.gsbox-path:gsbox}") String gsboxPath,
            @Value("${obraviva.conversion.timeout-seconds:600}") long timeoutSeconds) {

        return new GsboxSplatConverter(
                gsboxPath, Duration.ofSeconds(timeoutSeconds), processRunner, archiveExtractor);
    }
}