package com.obraviva.platform.reports;

import com.obraviva.platform.ai.ChatCompletion;
import com.obraviva.platform.reports.narration.ReportNarrator;
import com.obraviva.platform.reports.narration.gemini.GeminiReportNarrator;
import com.obraviva.platform.reports.narration.mock.MockReportNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Selección del adaptador de redacción.
 *
 * <p>Condicional explícito sobre una propiedad, no {@code @ConditionalOnProperty}:
 * mismo criterio que en los módulos de reconstrucción e inteligencia artificial. El
 * paquete de esa anotación cambió entre versiones de Boot, y el condicional permite
 * además dejar registrado en el arranque qué adaptador quedó activo.
 */
@Configuration
public class ReportsConfig {

    private static final Logger log = LoggerFactory.getLogger(ReportsConfig.class);

    @Bean
    public ReportNarrator reportNarrator(
            ChatCompletion chatCompletion,
            ReportDataCodec codec,
            @Value("${obraviva.reports.provider:mock}") String provider,
            @Value("${obraviva.reports.prompt-version:v1}") String promptVersion,
            @Value("${obraviva.reports.model-id:gemini-3.6-flash}") String modelId) {

        if ("mock".equalsIgnoreCase(provider)) {
            log.warn("Adaptador de redacción de reportes SIMULADO activo. "
                    + "No se consume cuota del proveedor de modelo de lenguaje.");
            return new MockReportNarrator();
        }

        if ("gemini".equalsIgnoreCase(provider)) {
            log.warn("Adaptador de redacción de reportes REAL activo (modelo {}, prompt {}). "
                    + "Cada reporte consume cuota del proveedor.", modelId, promptVersion);
            return new GeminiReportNarrator(
                    chatCompletion, codec, loadPrompt(promptVersion), promptVersion, modelId);
        }

        throw new IllegalStateException(
                "Proveedor de redacción desconocido: '" + provider
                        + "'. Valores admitidos: mock, gemini.");
    }

    /** Los prompts se versionan como artefactos: la versión es parte del nombre. */
    private static String loadPrompt(String version) {
        String path = "prompts/report-narration-" + version + ".txt";
        try {
            return new String(
                    new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "No se pudo cargar la plantilla de prompt '" + path + "'", e);
        }
    }
}