package com.obraviva.platform.conversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extrae el modelo PLY del archivo comprimido que entrega el servicio de
 * reconstrucción.
 *
 * <p>El servicio devuelve un ZIP cuya estructura interna no está documentada. Esta
 * clase no asume nada sobre ella: recorre las entradas, se queda con el PLY más
 * grande y lo escribe con un nombre canónico.
 *
 * <p><strong>Nombre canónico.</strong> El PLY se escribe siempre como
 * {@code model.ply}, sin importar cómo se llame dentro del ZIP. Verificado
 * empíricamente: gsbox no encuentra archivos cuyo nombre contenga acentos, y un
 * ZIP de un tercero puede traer cualquier cosa.
 *
 * <p><strong>Validación de rutas.</strong> Una entrada de ZIP puede llamarse
 * {@code ../../algo} y escribir fuera del directorio de destino. El archivo viene
 * de un servicio externo, así que cada ruta se verifica antes de escribir.
 */
public class ArchiveExtractor {

    private static final Logger log = LoggerFactory.getLogger(ArchiveExtractor.class);

    private static final String CANONICAL_MODEL_NAME = "model.ply";

    /** Tope de tamaño descomprimido, para acotar el efecto de un archivo malicioso. */
    private final long maxExtractedBytes;

    public ArchiveExtractor(long maxExtractedBytes) {
        this.maxExtractedBytes = maxExtractedBytes;
    }

    /** Entrada candidata dentro del archivo comprimido. */
    private record PlyEntry(String name, long size) {
    }

    public Path extractModel(Path archive, Path workDir) {
        if (archive == null || !Files.isRegularFile(archive) || !Files.isReadable(archive)) {
            throw new SplatConversionException(
                    "No existe o no se puede leer el archivo comprimido: " + archive);
        }

        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new SplatConversionException(
                    "No se pudo crear el directorio de trabajo: " + workDir, e);
        }

        try (ZipFile zip = new ZipFile(archive.toFile())) {

            List<PlyEntry> candidates = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                requireSafeEntry(entry.getName(), workDir);
                if (entry.getName().toLowerCase().endsWith(".ply")) {
                    candidates.add(new PlyEntry(entry.getName(), entry.getSize()));
                }
            }

            if (candidates.isEmpty()) {
                throw new InvalidReconstructionArchiveException(
                        InvalidReconstructionArchiveException.Reason.NO_MODEL_FOUND,
                        "El archivo entregado por el servicio de reconstrucción no contiene "
                                + "ningún modelo PLY.");
            }

            // El más grande: si hay varios PLY, el modelo es el pesado. Los chicos
            // suelen ser nubes de puntos auxiliares o posiciones de cámara.
            PlyEntry chosen = candidates.stream()
                    .max((a, b) -> Long.compare(a.size(), b.size()))
                    .orElseThrow();

            if (candidates.size() > 1) {
                log.info("El archivo contiene {} modelos PLY. Se usa el más grande: {} ({} bytes)",
                        candidates.size(), chosen.name(), chosen.size());
            }

            Path destination = workDir.resolve(CANONICAL_MODEL_NAME);
            ZipEntry entry = zip.getEntry(chosen.name());

            try (InputStream in = zip.getInputStream(entry)) {
                long written = Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                if (written > maxExtractedBytes) {
                    Files.deleteIfExists(destination);
                    throw new InvalidReconstructionArchiveException(
                            InvalidReconstructionArchiveException.Reason.UNSAFE_ENTRY,
                            "El modelo descomprimido supera el tamaño máximo permitido ("
                                    + written + " bytes).");
                }
                log.info("Modelo extraído de {} a {} ({} bytes)",
                        chosen.name(), destination.getFileName(), written);
            }

            return destination;

        } catch (IOException e) {
            throw new InvalidReconstructionArchiveException(
                    InvalidReconstructionArchiveException.Reason.NOT_AN_ARCHIVE,
                    "El archivo entregado por el servicio de reconstrucción no es un "
                            + "comprimido legible: " + e.getMessage());
        }
    }

    /**
     * Rechaza entradas que escaparían del directorio de destino (Zip Slip). Se valida
     * toda entrada, no sólo las que vamos a extraer: un nombre así indica que el
     * archivo entero es sospechoso.
     */
    private static void requireSafeEntry(String entryName, Path workDir) {
        Path resolved = workDir.resolve(entryName).normalize();
        if (!resolved.startsWith(workDir.normalize())) {
            throw new InvalidReconstructionArchiveException(
                    InvalidReconstructionArchiveException.Reason.UNSAFE_ENTRY,
                    "El archivo comprimido contiene una ruta insegura: " + entryName);
        }
    }
}