package com.obraviva.platform.reconstruction.mock;

import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionService;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Adaptador simulado del servicio de reconstrucción. Es el que se usa en el
 * desarrollo diario: no toca la red y no consume créditos.
 *
 * <p>Simula el comportamiento asincrónico real devolviendo PROCESSING durante una
 * cantidad configurable de consultas antes de resolver. Sin eso, el worker de
 * polling nunca ejercitaría su camino de espera.
 *
 * <p><strong>Modelo de muestra.</strong> Si se configura la ruta de un PLY real, el
 * mock lo empaqueta en el ZIP que entrega. Eso permite ejercitar el pipeline completo
 * —extracción, conversión con gsbox y publicación de la captura— sin consumir
 * créditos del servicio externo. Sin esa configuración se genera un PLY mínimo de
 * cabecera solamente, que sirve para las pruebas de orquestación pero que gsbox
 * rechaza por no contener splats.
 *
 * <p>El estado vive en memoria y se pierde al reiniciar. Es deliberado: un mock que
 * persistiera estado sería una segunda implementación que mantener.
 */
public class MockReconstructionService implements ReconstructionService {

    private static final Logger log = LoggerFactory.getLogger(MockReconstructionService.class);

    /** Desenlace que simula el mock, para poder ejercitar los tres caminos. */
    public enum Outcome {
        SUCCESS,
        FAILED,
        REJECTED
    }

    private static final String REFERENCE_PREFIX = "mock-";
    private static final String MODEL_ENTRY_NAME = "model.ply";

    private final int pollsBeforeResolution;
    private final Outcome outcome;
    private final Path samplePly;
    private final Map<String, AtomicInteger> pollCounts = new ConcurrentHashMap<>();

    public MockReconstructionService(int pollsBeforeResolution, Outcome outcome) {
        this(pollsBeforeResolution, outcome, null);
    }

    public MockReconstructionService(int pollsBeforeResolution, Outcome outcome, Path samplePly) {
        if (pollsBeforeResolution < 0) {
            throw new IllegalArgumentException("La cantidad de consultas no puede ser negativa");
        }
        this.pollsBeforeResolution = pollsBeforeResolution;
        this.outcome = outcome;
        this.samplePly = samplePly;
    }

    @Override
    public String submitVideo(Path videoFile) {
        if (videoFile == null || !Files.isRegularFile(videoFile) || !Files.isReadable(videoFile)) {
            throw new ReconstructionException(
                    "No existe o no se puede leer el video a enviar: " + videoFile);
        }

        if (outcome == Outcome.REJECTED) {
            throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.VIDEO_REJECTED,
                    "El servicio de reconstrucción rechazó el video porque no cumple sus requisitos.");
        }

        String reference = REFERENCE_PREFIX + UUID.randomUUID();
        pollCounts.put(reference, new AtomicInteger(0));
        log.info("[MOCK] Video enviado a reconstrucción. Referencia simulada: {}", reference);
        return reference;
    }

    @Override
    public ReconstructionStatus checkStatus(String externalReference) {
        AtomicInteger polls = requireKnown(externalReference);
        int current = polls.incrementAndGet();

        if (current <= pollsBeforeResolution) {
            return ReconstructionStatus.PROCESSING;
        }
        return outcome == Outcome.FAILED
                ? ReconstructionStatus.FAILED
                : ReconstructionStatus.SUCCEEDED;
    }

    @Override
    public void downloadResult(String externalReference, Path destination) {
        requireKnown(externalReference);

        if (outcome != Outcome.SUCCESS) {
            throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.NOT_FOUND,
                    "La reconstrucción no produjo un resultado descargable.");
        }

        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeArchive(destination, externalReference);
            log.info("[MOCK] Resultado simulado escrito en {} ({} bytes)",
                    destination, Files.size(destination));

        } catch (IOException e) {
            throw new ReconstructionException(
                    "No se pudo escribir el resultado simulado en " + destination, e);
        }
    }

    /**
     * Escribe el ZIP de resultado. El servicio real entrega un archivo comprimido, no
     * un PLY suelto, así que el mock entrega lo mismo: si devolviera un PLY, la etapa
     * de descompresión nunca se ejercitaría en desarrollo.
     */
    private void writeArchive(Path destination, String reference) throws IOException {
        try (OutputStream out = Files.newOutputStream(destination);
             ZipOutputStream zip = new ZipOutputStream(out)) {

            zip.putNextEntry(new ZipEntry(MODEL_ENTRY_NAME));
            if (hasSampleModel()) {
                Files.copy(samplePly, zip);
            } else {
                zip.write(placeholderPly(reference).getBytes(StandardCharsets.UTF_8));
            }
            zip.closeEntry();

            // Los ZIP reales suelen traer archivos acompañantes; incluir uno obliga a
            // que la etapa siguiente busque el PLY en vez de tomar la primera entrada.
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("Resultado simulado. No proviene del servicio externo."
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private boolean hasSampleModel() {
        if (samplePly == null) {
            return false;
        }
        if (!Files.isRegularFile(samplePly) || !Files.isReadable(samplePly)) {
            log.warn("[MOCK] El modelo de muestra configurado no existe o no se puede leer: {}. "
                    + "Se genera un PLY mínimo, que gsbox rechazará por no contener splats.",
                    samplePly);
            return false;
        }
        return true;
    }

    /**
     * PLY mínimo, sólo cabecera. Alcanza para las pruebas de orquestación pero no
     * para la conversión: gsbox lo rechaza por no contener ningún splat.
     */
    private static String placeholderPly(String reference) {
        return """
                ply
                format binary_little_endian 1.0
                comment archivo simulado por MockReconstructionService
                comment referencia %s
                element vertex 0
                property float x
                property float y
                property float z
                end_header
                """.formatted(reference);
    }

    private AtomicInteger requireKnown(String externalReference) {
        AtomicInteger polls = pollCounts.get(externalReference);
        if (polls == null) {
            throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.NOT_FOUND,
                    "La tarea de reconstrucción no existe en el servicio.");
        }
        return polls;
    }

    /** El simulado no trabaja con créditos: no hay número que mostrar. */
    @Override
    public OptionalInt availableCredits() {
        return OptionalInt.empty();
    }
}
