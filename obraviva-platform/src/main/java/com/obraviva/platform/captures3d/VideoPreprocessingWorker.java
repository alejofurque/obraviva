package com.obraviva.platform.captures3d;

import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.video.TranscodeResult;
import com.obraviva.platform.video.VideoProcessor;
import com.obraviva.platform.video.VideoValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Ejecuta el paso de preprocesamiento de video de la cola de reconstrucción.
 *
 * <p>Corre en tres tiempos deliberadamente separados: transacción corta para tomar el
 * trabajo, trabajo pesado sin transacción abierta, transacción corta para registrar el
 * desenlace. Un transcodificado puede tardar minutos y no debe retener una conexión.
 *
 * <p>Procesa un trabajo por ciclo. Si procesara todos los pendientes en un mismo tick,
 * un fallo técnico que devuelve el trabajo a la cola quemaría los reintentos de
 * inmediato; de a uno, cada reintento espera el intervalo de polling.
 *
 * <p>Al terminar bien, el trabajo vuelve a PENDING posicionado en el paso de envío al
 * servicio externo. Queda quieto ahí hasta que exista quien atienda ese paso: el
 * reclamo filtra por pasos soportados, así que nadie lo toma en el intervalo.
 */
@Component
public class VideoPreprocessingWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoPreprocessingWorker.class);

    private static final String NORMALIZED_CONTENT_TYPE = "video/mp4";

    private final VideoPreprocessingService preprocessingService;
    private final StorageService storageService;
    private final VideoProcessor videoProcessor;
    private final boolean enabled;

    public VideoPreprocessingWorker(
            VideoPreprocessingService preprocessingService,
            StorageService storageService,
            VideoProcessor videoProcessor,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.preprocessingService = preprocessingService;
        this.storageService = storageService;
        this.videoProcessor = videoProcessor;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.poll-interval-ms:10000}",
            initialDelayString = "${obraviva.jobs.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            processNext();
        } catch (RuntimeException e) {
            // El planificador cancela la tarea si una ejecución propaga una excepción.
            log.error("Error inesperado en el ciclo de preprocesamiento de video", e);
        }
    }

    /** Procesa un trabajo si hay alguno pendiente. Expuesto para poder ejercitarlo en tests. */
    public void processNext() {
        Optional<VideoPreprocessingTask> task = preprocessingService.claimNext();
        task.ifPresent(this::process);
    }

    private void process(VideoPreprocessingTask task) {
        log.info("Preprocesando video de la captura {} (trabajo {})",
                task.captureId(), task.jobId());

        Path source = null;
        Path output = null;
        try {
            source = Files.createTempFile("obraviva-video-src-", ".bin");
            output = Files.createTempFile("obraviva-video-out-", ".mp4");

            storageService.download(task.sourceKey(), source);
            TranscodeResult result = videoProcessor.prepareForReconstruction(source, output);
            storageService.upload(result.outputFile(), task.targetKey(), NORMALIZED_CONTENT_TYPE);

            preprocessingService.recordSuccess(task.jobId());

            log.info("Video de la captura {} preprocesado y almacenado en {}. {}",
                    task.captureId(), task.targetKey(), result.summary());

        } catch (VideoValidationException e) {
            log.info("Video de la captura {} rechazado: {}", task.captureId(), e.userMessage());
            preprocessingService.recordValidationFailure(task.jobId(), e.userMessage());

        } catch (IOException e) {
            log.error("No se pudieron crear los archivos temporales del trabajo {}", task.jobId(), e);
            preprocessingService.recordTechnicalFailure(
                    task.jobId(), "Error de disco al preparar el procesamiento: " + e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico preprocesando la captura {}", task.captureId(), e);
            preprocessingService.recordTechnicalFailure(task.jobId(), String.valueOf(e.getMessage()));

        } finally {
            deleteQuietly(source);
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("No se pudo borrar el archivo temporal {}", path, e);
        }
    }
}