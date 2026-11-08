package com.obraviva.platform.reconstruction;

import com.obraviva.platform.reconstruction.kiri.KiriReconstructionService;
import com.obraviva.platform.reconstruction.mock.MockReconstructionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Selección del adaptador de reconstrucción.
 *
 * <p>La elección se resuelve con un condicional explícito en vez de
 * {@code @ConditionalOnProperty}: el paquete de esa anotación cambió entre versiones
 * de Spring Boot y ya nos costó rondas de errores asumir ubicaciones. Un condicional
 * sobre una propiedad no depende de ninguna versión, y permite loguear en el arranque
 * qué adaptador quedó activo — dato que conviene ver cuando lo que está en juego es
 * gastar créditos reales.
 */
@Configuration
public class ReconstructionConfig {

    private static final Logger log = LoggerFactory.getLogger(ReconstructionConfig.class);

    @Bean
    public ReconstructionService reconstructionService(
            @Value("${obraviva.reconstruction.provider:mock}") String provider,
            @Value("${obraviva.reconstruction.mock.polls-before-resolution:2}") int polls,
            @Value("${obraviva.reconstruction.mock.outcome:SUCCESS}") String outcome,
            @Value("${obraviva.reconstruction.mock.sample-ply:}") String samplePly,
            @Value("${obraviva.reconstruction.kiri.base-url:https://api.kiriengine.app/api}") String baseUrl,
            @Value("${obraviva.reconstruction.kiri.api-key:}") String apiKey,
            @Value("${obraviva.reconstruction.kiri.connect-timeout-seconds:30}") long connectTimeout,
            @Value("${obraviva.reconstruction.kiri.request-timeout-seconds:60}") long requestTimeout,
            @Value("${obraviva.reconstruction.kiri.upload-timeout-seconds:900}") long uploadTimeout) {

        if ("mock".equalsIgnoreCase(provider)) {
            MockReconstructionService.Outcome parsed =
                    MockReconstructionService.Outcome.valueOf(outcome.toUpperCase());
            Path sample = (samplePly == null || samplePly.isBlank()) ? null : Path.of(samplePly);

            log.warn("Adaptador de reconstrucción SIMULADO activo (desenlace: {}). "
                    + "No se consumen créditos del servicio externo.", parsed);
            if (sample != null) {
                log.warn("El resultado simulado empaquetará el modelo de muestra: {}", sample);
            }
            return new MockReconstructionService(polls, parsed, sample);
        }

        if ("kiri".equalsIgnoreCase(provider)) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "El proveedor 'kiri' está activo pero no hay clave de API. "
                                + "Definí KIRI_API_KEY en obraviva-platform/.env.");
            }
            // El aviso es deliberadamente ruidoso: cada envío cuesta un crédito de un
            // presupuesto de diez que no se puede recargar por menos de 500 dólares.
            log.warn("Adaptador de reconstrucción REAL activo contra {}. "
                    + "Cada envío de video CONSUME UN CRÉDITO.", baseUrl);
            return new KiriReconstructionService(
                    baseUrl,
                    apiKey,
                    Duration.ofSeconds(connectTimeout),
                    Duration.ofSeconds(requestTimeout),
                    Duration.ofSeconds(uploadTimeout));
        }

        throw new IllegalStateException(
                "Proveedor de reconstrucción desconocido: '" + provider + "'. Valores admitidos: mock, kiri.");
    }
}