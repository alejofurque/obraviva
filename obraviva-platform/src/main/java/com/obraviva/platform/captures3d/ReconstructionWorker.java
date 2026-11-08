package com.obraviva.platform.captures3d;

import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionService;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Ejecuta los pasos de la cola que dialogan con el servicio externo de reconstrucción:
 * envío, espera del resultado y descarga.
 *
 * <p>Vive separado de {@link VideoPreprocessingWorker} a propósito: aquel hace trabajo
 * local pesado y éste habla con un tercero. Además corre en su propio intervalo, más
 * largo, porque el servicio externo tarda entre siete y veinte minutos en resolver.
 *
 * <p><strong>El envío consume un crédito.</strong> Con el adaptador simulado activo
 * —el valor por omisión— no se gasta nada; con el adaptador real, cada ejecución
 * exitosa del paso de envío descuenta uno de los diez créditos disponibles.
 */
@Component
public class ReconstructionWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconstructionWorker.class);

    private static final String RESULT_CONTENT_TYPE = "application/zip";

    private final ReconstructionJobService reconstructionJobService;
    private final StorageService storageService;
    private final ReconstructionService reconstructionService;
    private final boolean enabled;
    private final Duration maxWaitForExternal;

    public ReconstructionWorker(
            ReconstructionJobService reconstructionJobService,
            StorageService storageService,
            ReconstructionService reconstructionService,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled,
            @Value("${obraviva.jobs.max-external-wait-minutes:120}") long maxWaitMinutes) {
        this.reconstructionJobService = reconstructionJobService;
        this.storageService = storageService;
        this.reconstructionService = reconstructionService;
        this.enabled = enabled;
        this.maxWaitForExternal = Duration.ofMinutes(maxWaitMinutes);
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.reconstruction-poll-interval-ms:30000}",
            initialDelayString = "${obraviva.jobs.reconstruction-initial-delay-ms:20000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            processNext();
        } catch (RuntimeException e) {
            // El planificador cancela la tarea si una ejecución propaga una excepción.
            log.error("Error inesperado en el ciclo de reconstrucción", e);
        }
    }

    /** Procesa un trabajo si hay alguno pendiente. Expuesto para poder ejercitarlo en tests. */
    public void processNext() {
        Optional<ClaimedStep> claimed = reconstructionJobService.claimNext();
        claimed.ifPresent(step -> {
            switch (step.step()) {
                case SUBMIT -> submit(step.asSubmission());
                case AWAIT_EXTERNAL -> checkStatus(step.asPoll());
                case DOWNLOAD -> download(step.asDownload());
                default -> log.warn("Paso inesperado en el worker de reconstrucción: {}", step.step());
            }
        });
    }

    // --- Envío ---

    private void submit(ReconstructionSubmissionTask task) {
        log.info("Enviando a reconstrucción el video de la captura {} (trabajo {})",
                task.captureId(), task.jobId());

        Path video = null;
        try {
            video = Files.createTempFile("obraviva-submit-", ".mp4");
            storageService.download(task.normalizedVideoKey(), video);

            String externalReference = reconstructionService.submitVideo(video);
            reconstructionJobService.recordSubmission(task.jobId(), externalReference);

            log.info("Captura {} aceptada por el servicio de reconstrucción. Referencia: {}",
                    task.captureId(), externalReference);

        } catch (ReconstructionRejectedException e) {
            log.warn("El servicio rechazó la captura {} ({}): {}",
                    task.captureId(), e.reason(), e.userMessage());
            reconstructionJobService.recordRejection(task.jobId(), e.userMessage());

        } catch (IOException e) {
            log.error("No se pudo crear el archivo temporal del trabajo {}", task.jobId(), e);
            reconstructionJobService.recordTechnicalFailure(
                    task.jobId(), "Error de disco al preparar el envío: " + e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico enviando la captura {} a reconstrucción", task.captureId(), e);
            reconstructionJobService.recordTechnicalFailure(
                    task.jobId(), String.valueOf(e.getMessage()));

        } finally {
            deleteQuietly(video);
        }
    }

    // --- Espera ---

    private void checkStatus(ReconstructionPollTask task) {
        // El corte es por tiempo y no por intentos: cada espera reinicia el contador a
        // propósito, así que 'attempts' nunca crece mientras el tercero trabaja. La
        // única cota posible es la marca de entrada al paso.
        if (task.timeInStep().compareTo(maxWaitForExternal) > 0) {
            log.error("La reconstrucción de la captura {} lleva {} minutos sin resolverse. "
                            + "Se abandona la espera. El crédito consumido no se recupera.",
                    task.captureId(), task.timeInStep().toMinutes());
            reconstructionJobService.recordRejection(task.jobId(),
                    "El servicio de reconstrucción no devolvió un resultado en el tiempo esperado.");
            return;
        }

        try {
            ReconstructionStatus status = reconstructionService.checkStatus(task.externalReference());

            if (!status.isTerminal()) {
                log.debug("La reconstrucción de la captura {} sigue en curso ({}). Esperando {} min.",
                        task.captureId(), status, task.timeInStep().toMinutes());
                reconstructionJobService.recordStillProcessing(task.jobId());
                return;
            }

            if (status.isSuccessful()) {
                log.info("Reconstrucción de la captura {} terminada tras {} minutos. "
                                + "Se procede a descargar el resultado.",
                        task.captureId(), task.timeInStep().toMinutes());
                reconstructionJobService.recordReadyForDownload(task.jobId());
                return;
            }

            // FAILED o EXPIRED: el crédito ya se gastó y no hay nada que descargar.
            log.error("La reconstrucción de la captura {} terminó en estado {}. "
                    + "El crédito consumido no se recupera.", task.captureId(), status);
            reconstructionJobService.recordRejection(task.jobId(),
                    status == ReconstructionStatus.EXPIRED
                            ? "El resultado de la reconstrucción venció antes de poder descargarse."
                            : "El servicio de reconstrucción no pudo procesar el video.");

        } catch (ReconstructionRejectedException e) {
            log.warn("Consulta de estado de la captura {} rechazada ({}): {}",
                    task.captureId(), e.reason(), e.userMessage());
            reconstructionJobService.recordRejection(task.jobId(), e.userMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico consultando el estado de la captura {}", task.captureId(), e);
            reconstructionJobService.recordTechnicalFailure(
                    task.jobId(), String.valueOf(e.getMessage()));
        }
    }

    // --- Descarga ---

    private void download(ReconstructionDownloadTask task) {
        // Urgente por diseño: el servicio conserva el resultado sólo tres días y el
        // enlace de descarga vive sesenta minutos.
        log.info("Descargando el resultado de la captura {} (trabajo {})",
                task.captureId(), task.jobId());

        Path archive = null;
        try {
            archive = Files.createTempFile("obraviva-result-", ".zip");

            reconstructionService.downloadResult(task.externalReference(), archive);
            storageService.upload(archive, task.resultKey(), RESULT_CONTENT_TYPE);
            reconstructionJobService.recordDownloaded(task.jobId());

            log.info("Resultado de la captura {} almacenado en {} ({} bytes)",
                    task.captureId(), task.resultKey(), sizeOrUnknown(archive));

        } catch (ReconstructionRejectedException e) {
            log.error("No se pudo descargar el resultado de la captura {} ({}): {}",
                    task.captureId(), e.reason(), e.userMessage());
            reconstructionJobService.recordRejection(task.jobId(), e.userMessage());

        } catch (IOException e) {
            log.error("No se pudo crear el archivo temporal del trabajo {}", task.jobId(), e);
            reconstructionJobService.recordTechnicalFailure(
                    task.jobId(), "Error de disco al preparar la descarga: " + e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico descargando el resultado de la captura {}", task.captureId(), e);
            reconstructionJobService.recordTechnicalFailure(
                    task.jobId(), String.valueOf(e.getMessage()));

        } finally {
            deleteQuietly(archive);
        }
    }

    // --- Utilidades ---

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

    private static String sizeOrUnknown(Path path) {
        try {
            return String.valueOf(Files.size(path));
        } catch (IOException e) {
            return "desconocido";
        }
    }
}