package com.obraviva.platform.conversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de la extracción del modelo. No necesitan Spring, Docker ni herramientas
 * externas: los archivos comprimidos se construyen en el propio test.
 */
class ArchiveExtractorTest {

    private final ArchiveExtractor extractor = new ArchiveExtractor(100L * 1024 * 1024);

    @TempDir
    Path tempDir;

    private Path workDir() throws IOException {
        Path dir = tempDir.resolve("trabajo");
        Files.createDirectories(dir);
        return dir;
    }

    private Path zipWith(String fileName, Map<String, String> entries) throws IOException {
        Path archive = tempDir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    @Test
    @DisplayName("extrae el PLY con nombre canónico, sin importar cómo se llame adentro")
    void extractsPlyWithCanonicalName() throws IOException {
        // Nombre con acentos y espacios a propósito: gsbox no los maneja.
        Path archive = zipWith("resultado.zip",
                Map.of("Reconstrucción Nº1.ply", "contenido del modelo"));

        Path model = extractor.extractModel(archive, workDir());

        assertThat(model.getFileName().toString()).isEqualTo("model.ply");
        assertThat(Files.readString(model)).isEqualTo("contenido del modelo");
    }

    @Test
    @DisplayName("elige el PLY más grande cuando hay varios")
    void picksTheLargestPly() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("cameras.ply", "chico");
        entries.put("model.ply", "este es el modelo grande de verdad, mucho más pesado");
        entries.put("points.ply", "mediano pero no tanto");

        Path archive = zipWith("varios.zip", entries);

        Path model = extractor.extractModel(archive, workDir());

        assertThat(Files.readString(model)).contains("modelo grande");
    }

    @Test
    @DisplayName("ignora los archivos que no son PLY")
    void ignoresNonPlyEntries() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("readme.txt", "texto mucho más largo que el modelo para que no gane por tamaño");
        entries.put("preview.jpg", "datos de imagen bastante largos también");
        entries.put("scene.ply", "modelo");

        Path archive = zipWith("mixto.zip", entries);

        Path model = extractor.extractModel(archive, workDir());

        assertThat(Files.readString(model)).isEqualTo("modelo");
    }

    @Test
    @DisplayName("encuentra el PLY aunque esté en un subdirectorio")
    void findsPlyInsideSubdirectory() throws IOException {
        Path archive = zipWith("anidado.zip",
                Map.of("salida/modelos/scene.ply", "modelo anidado"));

        Path model = extractor.extractModel(archive, workDir());

        assertThat(Files.readString(model)).isEqualTo("modelo anidado");
    }

    @Test
    @DisplayName("rechaza un comprimido sin ningún PLY")
    void rejectsArchiveWithoutPly() throws IOException {
        Path archive = zipWith("vacio.zip", Map.of("readme.txt", "nada útil"));

        assertThatThrownBy(() -> extractor.extractModel(archive, workDir()))
                .isInstanceOf(InvalidReconstructionArchiveException.class)
                .satisfies(thrown -> assertThat(
                        ((InvalidReconstructionArchiveException) thrown).reason())
                        .isEqualTo(InvalidReconstructionArchiveException.Reason.NO_MODEL_FOUND));
    }

    @Test
    @DisplayName("rechaza un archivo que no es un comprimido válido")
    void rejectsNonArchive() throws IOException {
        Path fake = tempDir.resolve("no-es-zip.zip");
        Files.writeString(fake, "esto es texto plano, no un archivo comprimido");

        assertThatThrownBy(() -> extractor.extractModel(fake, workDir()))
                .isInstanceOf(InvalidReconstructionArchiveException.class)
                .satisfies(thrown -> assertThat(
                        ((InvalidReconstructionArchiveException) thrown).reason())
                        .isEqualTo(InvalidReconstructionArchiveException.Reason.NOT_AN_ARCHIVE));
    }

    @Test
    @DisplayName("rechaza rutas que escaparían del directorio de destino")
    void rejectsPathTraversalEntries() throws IOException {
        // Zip Slip: el archivo viene de un servicio externo y una entrada puede
        // intentar escribir fuera del destino.
        Path archive = zipWith("malicioso.zip",
                Map.of("../../escapado.ply", "contenido"));

        assertThatThrownBy(() -> extractor.extractModel(archive, workDir()))
                .isInstanceOf(InvalidReconstructionArchiveException.class)
                .satisfies(thrown -> assertThat(
                        ((InvalidReconstructionArchiveException) thrown).reason())
                        .isEqualTo(InvalidReconstructionArchiveException.Reason.UNSAFE_ENTRY));
    }

    @Test
    @DisplayName("un archivo inexistente es una falla técnica")
    void missingArchiveIsTechnicalFailure() throws IOException {
        assertThatThrownBy(() ->
                extractor.extractModel(tempDir.resolve("no-existe.zip"), workDir()))
                .isInstanceOf(SplatConversionException.class);
    }
}