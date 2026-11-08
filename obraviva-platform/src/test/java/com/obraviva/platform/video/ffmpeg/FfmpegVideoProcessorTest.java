package com.obraviva.platform.video.ffmpeg;

import com.obraviva.platform.video.TranscodeResult;
import com.obraviva.platform.video.VideoConstraints;
import com.obraviva.platform.video.VideoMetadata;
import com.obraviva.platform.video.VideoValidationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests del adaptador de preprocesamiento. No levantan contexto de Spring ni Docker:
 * generan el material de prueba con el propio ffmpeg (fuente sintética lavfi) y verifican
 * el comportamiento real de la herramienta externa.
 *
 * <p>Si ffmpeg no está en el PATH del proceso de Maven, la clase entera se omite en vez
 * de fallar. Atención al leer el resultado: eso se reporta como "Tests run: 0" con build
 * exitoso, que a simple vista parece verde. El conteo esperado es 8.
 */
class FfmpegVideoProcessorTest {

    private static final ProcessRunner RUNNER = new ProcessRunner();

    private static final VideoConstraints CONSTRAINTS = new VideoConstraints(
            1920, 1080, 180, 20, Duration.ofSeconds(60), Duration.ofSeconds(900));

    private final FfmpegVideoProcessor processor =
            new FfmpegVideoProcessor("ffmpeg", "ffprobe", CONSTRAINTS, RUNNER);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requireFfmpeg() {
        Assumptions.assumeTrue(
                commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "ffmpeg/ffprobe no están disponibles en el PATH: se omiten los tests de preprocesamiento de video");
    }

    @Test
    @DisplayName("probe devuelve dimensiones, duración y códec del video")
    void probeReturnsMetadata() {
        Path video = generateVideo("origen.mp4", "640x480", 30, 30);

        VideoMetadata metadata = processor.probe(video);

        assertThat(metadata.width()).isEqualTo(640);
        assertThat(metadata.height()).isEqualTo(480);
        assertThat(metadata.durationSeconds()).isCloseTo(30.0, within(1.0));
        assertThat(metadata.videoCodec()).isEqualTo("h264");
        assertThat(metadata.sizeBytes()).isPositive();
    }

    @Test
    @DisplayName("rechaza un archivo sin pista de video")
    void rejectsFileWithoutVideoStream() {
        Path audioOnly = generateAudioOnly("solo-audio.m4a", 30);

        assertThatThrownBy(() -> processor.probe(audioOnly))
                .isInstanceOf(VideoValidationException.class)
                .satisfies(thrown -> assertThat(((VideoValidationException) thrown).reason())
                        .isEqualTo(VideoValidationException.Reason.NO_VIDEO_STREAM));
    }

    @Test
    @DisplayName("rechaza un video más corto que el mínimo")
    void rejectsVideoShorterThanMinimum() {
        Path video = generateVideo("corto.mp4", "640x480", 30, 5);
        Path output = tempDir.resolve("salida-corto.mp4");

        assertThatThrownBy(() -> processor.prepareForReconstruction(video, output))
                .isInstanceOf(VideoValidationException.class)
                .satisfies(thrown -> assertThat(((VideoValidationException) thrown).reason())
                        .isEqualTo(VideoValidationException.Reason.TOO_SHORT));

        assertThat(output).doesNotExist();
    }

    @Test
    @DisplayName("reduce un video horizontal que excede el límite a 1920x1080")
    void downscalesHorizontalVideoAboveLimit() {
        Path video = generateVideo("horizontal-grande.mp4", "2560x1440", 24, 25);
        Path output = tempDir.resolve("salida-horizontal.mp4");

        TranscodeResult result = processor.prepareForReconstruction(video, output);

        assertThat(result.downscaled()).isTrue();
        assertThat(result.trimmed()).isFalse();
        assertThat(result.outputMetadata().width()).isEqualTo(1920);
        assertThat(result.outputMetadata().height()).isEqualTo(1080);
        assertThat(output).exists();
    }

    @Test
    @DisplayName("reduce un video vertical a 1080x1920, respetando su orientación")
    void downscalesVerticalVideoPreservingOrientation() {
        // Caso de uso principal del producto: el encargado filma con el teléfono en
        // vertical. Con una caja no orientada este video terminaría en 608x1080.
        Path video = generateVideo("vertical-grande.mp4", "1440x2560", 24, 25);
        Path output = tempDir.resolve("salida-vertical.mp4");

        TranscodeResult result = processor.prepareForReconstruction(video, output);

        assertThat(result.downscaled()).isTrue();
        assertThat(result.outputMetadata().width()).isEqualTo(1080);
        assertThat(result.outputMetadata().height()).isEqualTo(1920);
    }

    @Test
    @DisplayName("no amplía un video más chico que los límites")
    void neverUpscalesSmallVideo() {
        Path video = generateVideo("chico.mp4", "640x480", 30, 25);
        Path output = tempDir.resolve("salida-chico.mp4");

        TranscodeResult result = processor.prepareForReconstruction(video, output);

        assertThat(result.downscaled()).isFalse();
        assertThat(result.outputMetadata().width()).isEqualTo(640);
        assertThat(result.outputMetadata().height()).isEqualTo(480);
    }

    @Test
    @DisplayName("descarta el audio del video de origen")
    void dropsAudioTrack() {
        Path video = generateVideoWithAudio("con-audio.mp4", "640x480", 25);
        Path output = tempDir.resolve("salida-sin-audio.mp4");

        processor.prepareForReconstruction(video, output);

        ProcessRunner.ProcessResult probe = RUNNER.run(List.of(
                "ffprobe", "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=codec_type",
                "-of", "default=noprint_wrappers=1",
                output.toAbsolutePath().toString()
        ), Duration.ofSeconds(60));

        assertThat(probe.exitCode()).isZero();
        assertThat(probe.stdout()).doesNotContain("audio");
    }

    @Test
    @DisplayName("recorta un video más largo que el máximo")
    void trimsVideoLongerThanMaximum() {
        Path video = generateVideo("largo.mp4", "320x240", 10, 200);
        Path output = tempDir.resolve("salida-largo.mp4");

        TranscodeResult result = processor.prepareForReconstruction(video, output);

        assertThat(result.trimmed()).isTrue();
        assertThat(result.outputMetadata().durationSeconds()).isCloseTo(180.0, within(1.5));
    }

    // --- utilidades de generación de material de prueba ---

    private Path generateVideo(String fileName, String size, int fps, int seconds) {
        Path output = tempDir.resolve(fileName);
        ProcessRunner.ProcessResult result = RUNNER.run(List.of(
                "ffmpeg", "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "testsrc=size=" + size + ":rate=" + fps + ":duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                output.toAbsolutePath().toString()
        ), Duration.ofSeconds(300));

        assertThat(result.exitCode())
                .withFailMessage("No se pudo generar el video de prueba: %s", result.stderrTail(500))
                .isZero();
        return output;
    }

    private Path generateVideoWithAudio(String fileName, String size, int seconds) {
        Path output = tempDir.resolve(fileName);
        ProcessRunner.ProcessResult result = RUNNER.run(List.of(
                "ffmpeg", "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "testsrc=size=" + size + ":rate=30:duration=" + seconds,
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest",
                output.toAbsolutePath().toString()
        ), Duration.ofSeconds(300));

        assertThat(result.exitCode())
                .withFailMessage("No se pudo generar el video con audio de prueba: %s", result.stderrTail(500))
                .isZero();
        return output;
    }

    private Path generateAudioOnly(String fileName, int seconds) {
        Path output = tempDir.resolve(fileName);
        ProcessRunner.ProcessResult result = RUNNER.run(List.of(
                "ffmpeg", "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=" + seconds,
                "-c:a", "aac",
                output.toAbsolutePath().toString()
        ), Duration.ofSeconds(120));

        assertThat(result.exitCode())
                .withFailMessage("No se pudo generar el audio de prueba: %s", result.stderrTail(500))
                .isZero();
        return output;
    }

    private static boolean commandAvailable(String command) {
        try {
            return RUNNER.run(List.of(command, "-version"), Duration.ofSeconds(20)).exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}