package com.obraviva.platform.conversion.gsbox;

import com.obraviva.platform.common.process.ProcessRunner;
import com.obraviva.platform.conversion.ArchiveExtractor;
import com.obraviva.platform.conversion.ConversionResult;
import com.obraviva.platform.conversion.InvalidReconstructionArchiveException;
import com.obraviva.platform.conversion.SplatConversionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador de gsbox contra la herramienta real.
 *
 * <p>El PLY de prueba se construye en el propio test: un modelo mínimo con unos
 * pocos splats sintéticos, suficiente para que gsbox lo procese de verdad. No hace
 * falta material real ni archivos versionados.
 *
 * <p>Si gsbox no está en el PATH del proceso de Maven, la clase entera se omite en
 * vez de fallar. Atención al leer el resultado: eso se reporta como "Tests run: 0"
 * con build exitoso. El conteo esperado es 4.
 */
class GsboxSplatConverterTest {

    private static final ProcessRunner RUNNER = new ProcessRunner();

    private final GsboxSplatConverter converter = new GsboxSplatConverter(
            "gsbox",
            Duration.ofSeconds(120),
            RUNNER,
            new ArchiveExtractor(100L * 1024 * 1024));

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requireGsbox() {
        Assumptions.assumeTrue(
                gsboxAvailable(),
                "gsbox no está disponible en el PATH: se omiten los tests de conversión");
    }

    /**
     * Escribe un PLY binario mínimo con los campos que espera un modelo 3DGS de
     * grado 0: posición, normal, color base, opacidad, escala y rotación.
     */
    private Path minimalPly(String fileName, int splatCount) throws IOException {
        Path ply = tempDir.resolve(fileName);

        String header = """
                ply
                format binary_little_endian 1.0
                element vertex %d
                property float x
                property float y
                property float z
                property float nx
                property float ny
                property float nz
                property float f_dc_0
                property float f_dc_1
                property float f_dc_2
                property float opacity
                property float scale_0
                property float scale_1
                property float scale_2
                property float rot_0
                property float rot_1
                property float rot_2
                property float rot_3
                end_header
                """.formatted(splatCount);

        // 17 propiedades float por vértice, little endian.
        int propertiesPerVertex = 17;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer
                .allocate(splatCount * propertiesPerVertex * Float.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < splatCount; i++) {
            buffer.putFloat(i * 0.1f);   // x
            buffer.putFloat(i * 0.05f);  // y
            buffer.putFloat(i * 0.02f);  // z
            buffer.putFloat(0f);         // nx
            buffer.putFloat(0f);         // ny
            buffer.putFloat(1f);         // nz
            buffer.putFloat(0.5f);       // f_dc_0
            buffer.putFloat(0.3f);       // f_dc_1
            buffer.putFloat(0.2f);       // f_dc_2
            buffer.putFloat(2.0f);       // opacity (logit)
            buffer.putFloat(-3.0f);      // scale_0 (log)
            buffer.putFloat(-3.0f);      // scale_1
            buffer.putFloat(-3.0f);      // scale_2
            buffer.putFloat(1f);         // rot_0 (w)
            buffer.putFloat(0f);         // rot_1
            buffer.putFloat(0f);         // rot_2
            buffer.putFloat(0f);         // rot_3
        }

        try (var out = Files.newOutputStream(ply)) {
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(buffer.array());
        }
        return ply;
    }

    @Test
    @DisplayName("convierte un PLY a un SPZ legible por la propia herramienta")
    void convertsPlyToReadableSpz() throws IOException {
        Path ply = minimalPly("entrada.ply", 500);
        Path spz = tempDir.resolve("salida.spz");

        ConversionResult result = converter.convertToSpz(ply, spz);

        assertThat(spz).exists();
        assertThat(result.outputSizeBytes()).isPositive();
        assertThat(result.sourceSizeBytes()).isEqualTo(Files.size(ply));

        // La herramienta debe poder releer lo que escribió.
        ProcessRunner.ProcessResult info = RUNNER.run(
                List.of("gsbox", "info", "-i", spz.toAbsolutePath().toString()),
                Duration.ofSeconds(60));
        assertThat(info.exitCode()).isZero();
        assertThat(info.stdout()).contains("spz");
    }

    @Test
    @DisplayName("el SPZ generado declara la versión 2, que es la que lee el visor")
    void generatedSpzDeclaresVersion2() throws IOException {
        Path ply = minimalPly("version.ply", 300);
        Path spz = tempDir.resolve("version.spz");

        converter.convertToSpz(ply, spz);

        ProcessRunner.ProcessResult info = RUNNER.run(
                List.of("gsbox", "info", "-i", spz.toAbsolutePath().toString()),
                Duration.ofSeconds(60));

        // El default de gsbox es la versión más nueva; sin -ov explícito saldría v4,
        // que no está verificada contra Spark 2.1.0.
        assertThat(info.stdout()).containsPattern("Version\\s*:\\s*2");
    }

    @Test
    @DisplayName("la conversión preserva la cantidad de splats")
    void conversionPreservesSplatCount() throws IOException {
        Path ply = minimalPly("conteo.ply", 777);
        Path spz = tempDir.resolve("conteo.spz");

        converter.convertToSpz(ply, spz);

        ProcessRunner.ProcessResult info = RUNNER.run(
                List.of("gsbox", "info", "-i", spz.toAbsolutePath().toString()),
                Duration.ofSeconds(60));

        assertThat(info.stdout()).containsPattern("NumPoints\\s*:\\s*777");
    }

        @Test
    @DisplayName("un PLY que la herramienta no entiende es un rechazo definitivo, no técnico")
    void unsupportedPlyIsRejectedNotRetried() throws IOException {
        Path notAPly = tempDir.resolve("basura.ply");
        Files.writeString(notAPly, "esto no es un modelo");

        // Reintentar con el mismo archivo daría el mismo resultado: el error debe
        // ser de la familia definitiva para no quemar los reintentos del trabajo.
        assertThatThrownBy(() ->
                converter.convertToSpz(notAPly, tempDir.resolve("nada.spz")))
                .isInstanceOf(InvalidReconstructionArchiveException.class)
                .hasMessageContaining("no pudo procesarse");
    }

    @Test
    @DisplayName("un PLY vacío tampoco se reintenta")
    void emptyPlyIsRejected() throws IOException {
        // Es exactamente lo que genera el adaptador simulado sin modelo de muestra:
        // cabecera válida con cero vértices.
        Path emptyPly = minimalPly("vacio.ply", 0);

        assertThatThrownBy(() ->
                converter.convertToSpz(emptyPly, tempDir.resolve("vacio.spz")))
                .isInstanceOf(InvalidReconstructionArchiveException.class);
    }

    private static boolean gsboxAvailable() {
        try {
            return RUNNER.run(List.of("gsbox", "-v"), Duration.ofSeconds(20)).exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}