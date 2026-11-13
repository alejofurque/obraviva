package com.obraviva.platform.conversion.gsbox;

import com.obraviva.platform.common.process.ProcessRunner;
import com.obraviva.platform.conversion.ArchiveExtractor;
import com.obraviva.platform.conversion.ConversionResult;
import com.obraviva.platform.conversion.InvalidReconstructionArchiveException;
import com.obraviva.platform.conversion.SplatConversionException;
import com.obraviva.platform.conversion.SplatConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Conversión de PLY a SPZ mediante gsbox, invocado como proceso externo.
 *
 * <p><strong>Los parámetros de conversión son constantes y no configuración.</strong>
 * Cada uno salió de verificación experimental contra Spark 2.1.0 comparando A/B
 * con un archivo de referencia exportado por Scaniverse; exponerlos como propiedades
 * invitaría a cambiarlos sin repetir esa verificación, y un valor equivocado produce
 * archivos que no cargan o escenas invertidas.
 *
 * <ul>
 *   <li>{@code -ov 2} — versión de SPZ. Es la que exporta Scaniverse y la que el
 *       visor usa desde el principio. Spark también carga v3, pero v2 pesa
 *       menos (6,7 MB contra 7,1 MB para la misma escena).</li>
 *   <li>{@code -q 5} — calidad. Comparada A/B contra la calidad 9 con corte seco y
 *       cámara fija: diferencia visual imperceptible, 21% menos de tamaño. El ahorro
 *       importa en la descarga desde el teléfono del comprador.</li>
 *   <li>{@code -rx 180} — corrección de sistema de coordenadas. SPZ almacena en RUB
 *       (convención OpenGL/three.js) y PLY usa RDF; la diferencia es una rotación de
 *       180 grados sobre el eje X. Scaniverse aplica la conversión al exportar,
 *       gsbox no. Sin esto la escena aparece invertida. Verificado A/B: con la
 *       corrección, la salida queda idéntica a la referencia.</li>
 *   <li>Sin {@code -sh} — se preservan los armónicos esféricos de grado 3, que son
 *       los reflejos dependientes del ángulo. La experiencia visual es el producto.</li>
 * </ul>
 *
 * <p>Requiere gsbox 4.7.2 o superior: las versiones anteriores tenían un error en la
 * codificación de rotación de SPZ v3, que es el origen de los reportes antiguos de
 * degradación en la conversión.
 *
 * <p><strong>Salidas de la herramienta.</strong> gsbox escribe sus mensajes de error
 * en la salida estándar y no en la de error, así que los diagnósticos se arman
 * mirando las dos.
 */
public class GsboxSplatConverter implements SplatConverter {

    private static final Logger log = LoggerFactory.getLogger(GsboxSplatConverter.class);

    private static final String SPZ_VERSION = "2";
    private static final String QUALITY = "5";
    private static final String ROTATION_X_DEGREES = "180";

    private final String gsboxPath;
    private final Duration timeout;
    private final ProcessRunner processRunner;
    private final ArchiveExtractor archiveExtractor;

    public GsboxSplatConverter(
            String gsboxPath,
            Duration timeout,
            ProcessRunner processRunner,
            ArchiveExtractor archiveExtractor) {
        this.gsboxPath = gsboxPath;
        this.timeout = timeout;
        this.processRunner = processRunner;
        this.archiveExtractor = archiveExtractor;
    }

    @Override
    public Path extractModel(Path archive, Path workDir) {
        return archiveExtractor.extractModel(archive, workDir);
    }

    @Override
    public ConversionResult convertToSpz(Path plyFile, Path outputFile) {
        // Que el archivo exista y sea legible se verifica ANTES de invocar la
        // herramienta. Esto es lo que permite interpretar cualquier fallo posterior
        // como un problema del contenido y no del entorno.
        if (plyFile == null || !Files.isRegularFile(plyFile) || !Files.isReadable(plyFile)) {
            throw new SplatConversionException(
                    "No existe o no se puede leer el modelo PLY: " + plyFile);
        }

        createParentDirectories(outputFile);
        long sourceSize = fileSize(plyFile);

        List<String> command = List.of(
                gsboxPath,
                "p2z",
                "-i", plyFile.toAbsolutePath().toString(),
                "-o", outputFile.toAbsolutePath().toString(),
                "-ov", SPZ_VERSION,
                "-q", QUALITY,
                "-rx", ROTATION_X_DEGREES
        );

        log.info("Convirtiendo {} a SPZ v{} (calidad {}, corrección de orientación {} grados)",
                plyFile.getFileName(), SPZ_VERSION, QUALITY, ROTATION_X_DEGREES);

        ProcessRunner.ProcessResult result = processRunner.run(command, timeout);
        String output = combinedOutput(result);

        if (result.failed()) {
            throw rejectedModel(output);
        }

        // gsbox reporta parte de sus errores sin código de salida distinto de cero,
        // así que la existencia del archivo se verifica siempre.
        if (!Files.isRegularFile(outputFile) || fileSize(outputFile) == 0L) {
            throw rejectedModel(output);
        }

        ConversionResult conversionResult =
                new ConversionResult(outputFile, sourceSize, fileSize(outputFile));
        log.info("Conversión completada: {}", conversionResult.summary());
        return conversionResult;
    }

    /**
     * Un fallo de gsbox sobre un archivo que ya se verificó existente y legible es un
     * problema del contenido, no del entorno: reintentar con el mismo modelo da
     * exactamente el mismo resultado.
     *
     * <p>Se decide por la naturaleza del fallo y no por el texto del mensaje. gsbox
     * usa al menos dos formulaciones distintas para entradas inválidas ("unsupported
     * ply file" y "EOF", ambas observadas), y no hay razón para creer que sean las
     * únicas. Buscar cadenas concretas obligaría a ampliar la lista cada vez que
     * apareciera una entrada rara en producción.
     *
     * <p>Las fallas verdaderamente técnicas no llegan hasta acá: la ausencia de la
     * herramienta y el vencimiento del tiempo máximo los reporta el ejecutor de
     * procesos antes de que haya un código de salida que interpretar.
     */
    private static InvalidReconstructionArchiveException rejectedModel(String output) {
        log.warn("gsbox rechazó el modelo. Salida de la herramienta: {}", abbreviate(output));
        return new InvalidReconstructionArchiveException(
                InvalidReconstructionArchiveException.Reason.NO_MODEL_FOUND,
                "El modelo generado por el servicio de reconstrucción no pudo procesarse. "
                        + "Es probable que la reconstrucción no haya producido una escena válida; "
                        + "conviene repetir la captura con un recorrido más lento y completo "
                        + "del espacio.");
    }

    /**
     * gsbox mezcla información y errores entre las dos salidas: se consideran ambas
     * para no perder el diagnóstico.
     */
    private static String combinedOutput(ProcessRunner.ProcessResult result) {
        String stdout = result.stdout() == null ? "" : result.stdout().trim();
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout + System.lineSeparator() + stderr;
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new SplatConversionException(
                    "No se pudo determinar el tamaño del archivo: " + path, e);
        }
    }

    private static void createParentDirectories(Path outputFile) {
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SplatConversionException(
                    "No se pudo crear el directorio de salida: " + parent, e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(sin salida)";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}