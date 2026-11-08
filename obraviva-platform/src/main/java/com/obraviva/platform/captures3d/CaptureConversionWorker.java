package com.obraviva.platform.captures3d;

import com.obraviva.platform.conversion.ConversionResult;
import com.obraviva.platform.conversion.InvalidReconstructionArchiveException;
import com.obraviva.platform.conversion.SplatConverter;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Último paso del pipeline: convierte el resultado de la reconstrucción al formato
 * de distribución y publica la captura.
 *
 * <p>Baja el archivo comprimido del almacenamiento, extrae el modelo PLY, lo
 * convierte a SPZ y sube el resultado a la clave que la captura tiene reservada
 * desde su creación. Al terminar, la captura queda disponible en el visor y el
 * trabajo cerrado.
 *
 * <p>Usa el intervalo corto de la cola, no el largo de reconstrucción: acá no hay
 * ningún tercero a quien esperar, sólo trabajo local.
 *
 * <p>El PLY no se almacena por separado. El documento maestro pide conservar el
 * original como respaldo, y ya se cumple: el archivo comprimido completo queda en
 * el almacenamiento y el PLY está adentro. Guardarlo suelto duplicaría unos cien
 * megabytes por captura sin ganar nada.
 */
@Component
public class CaptureConversionWorker {

    private static final Logger log = LoggerFactory.getLogger(CaptureConversionWorker.class);

    private static final String SPZ_CONTENT_TYPE = "application/octet-stream";

    private final CaptureConversionService conversionService;
    private final StorageService storageService;
    private final SplatConverter splatConverter;
    private final boolean enabled;

    public CaptureConversionWorker(
            CaptureConversionService conversionService,
            StorageService storageService,
            SplatConverter splatConverter,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.conversionService = conversionService;
        this.storageService = storageService;
        this.splatConverter = splatConverter;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.poll-interval-ms:10000}",
            initialDelayString = "${obraviva.jobs.conversion-initial-delay-ms:25000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            processNext();
        } catch (RuntimeException e) {
            // El planificador cancela la tarea si una ejecución propaga una excepción.
            log.error("Error inesperado en el ciclo de conversión de capturas", e);
        }
    }

    /** Procesa un trabajo si hay alguno pendiente. Expuesto para poder ejercitarlo en tests. */
    public void processNext() {
        Optional<CaptureConversionTask> task = conversionService.claimNext();
        task.ifPresent(this::convert);
    }

    private void convert(CaptureConversionTask task) {
        log.info("Convirtiendo el resultado de la captura {} (trabajo {})",
                task.captureId(), task.jobId());

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("obraviva-convert-");

            Path archive = workDir.resolve("resultado.zip");
            storageService.download(task.resultKey(), archive);

            Path ply = splatConverter.extractModel(archive, workDir);
            Path spz = workDir.resolve("model.spz");
            ConversionResult result = splatConverter.convertToSpz(ply, spz);

            storageService.upload(result.outputFile(), task.spzKey(), SPZ_CONTENT_TYPE);
            conversionService.recordSuccess(task.jobId(), task.captureId());

            log.info("Captura {} lista en {}. {}",
                    task.captureId(), task.spzKey(), result.summary());

        } catch (InvalidReconstructionArchiveException e) {
            log.error("El resultado de la captura {} no sirve ({}): {}",
                    task.captureId(), e.reason(), e.getMessage());
            conversionService.recordInvalidResult(task.jobId(), e.getMessage());

        } catch (IOException e) {
            log.error("No se pudo crear el directorio temporal del trabajo {}", task.jobId(), e);
            conversionService.recordTechnicalFailure(
                    task.jobId(), "Error de disco al preparar la conversión: " + e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico convirtiendo la captura {}", task.captureId(), e);
            conversionService.recordTechnicalFailure(task.jobId(), String.valueOf(e.getMessage()));

        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Borra el directorio de trabajo completo. A diferencia de los otros pasos, acá
     * hay varios archivos temporales (comprimido, PLY extraído, SPZ) y algunos pesan
     * cientos de megabytes.
     */
    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("No se pudo borrar el archivo temporal {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("No se pudo limpiar el directorio temporal {}", directory, e);
        }
    }
}