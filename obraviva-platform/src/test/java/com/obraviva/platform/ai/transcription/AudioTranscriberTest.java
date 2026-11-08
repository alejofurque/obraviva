package com.obraviva.platform.ai.transcription;

import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del transcriptor contra ffmpeg real.
 *
 * <p>La transcripción necesita el modelo ggml, que pesa cientos de megabytes y no
 * está versionado. Si la variable de entorno {@code OBRAVIVA_WHISPER_MODEL} no
 * apunta a un archivo existente, los tests que dependen del modelo se omiten y sólo
 * corren los que verifican validaciones previas.
 *
 * <p>Atención al leer el resultado: una clase omitida se reporta como
 * "Tests run: 0" con build exitoso. El conteo esperado con modelo disponible es 5.
 */
class AudioTranscriberTest {

    private static final ProcessRunner RUNNER = new ProcessRunner();

    @TempDir
    Path tempDir;

    private static Path modelPath() {
        String configured = System.getenv("OBRAVIVA_WHISPER_MODEL");
        return (configured == null || configured.isBlank())
                ? Path.of("modelo-inexistente.bin")
                : Path.of(configured);
    }

    private AudioTranscriber transcriber() {
        return new WhisperAudioTranscriber(
                "ffmpeg", modelPath(), Duration.ofSeconds(300), RUNNER);
    }

    @BeforeAll
    static void requireFfmpeg() {
        Assumptions.assumeTrue(
                ffmpegAvailable(),
                "ffmpeg no está disponible en el PATH: se omiten los tests de transcripción");
    }

    /** Genera un tono sintético: audio válido, pero sin voz. */
    private Path toneAudio(String fileName, int seconds) {
        Path audio = tempDir.resolve(fileName);
        ProcessRunner.ProcessResult result = RUNNER.run(List.of(
                "ffmpeg", "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=" + seconds,
                "-c:a", "libopus",
                audio.toAbsolutePath().toString()
        ), Duration.ofSeconds(120));

        assertThat(result.exitCode())
                .withFailMessage("No se pudo generar el audio de prueba: %s", result.stderrTail(500))
                .isZero();
        return audio;
    }

    @Test
    @DisplayName("un archivo de audio inexistente es una falla técnica")
    void missingAudioIsTechnicalFailure() {
        assertThatThrownBy(() ->
                transcriber().transcribe(tempDir.resolve("no-existe.ogg")))
                .isInstanceOf(TranscriptionException.class)
                .hasMessageContaining("audio");
    }

    @Test
    @DisplayName("un modelo inexistente es una falla técnica, no del audio")
    void missingModelIsTechnicalFailure() throws IOException {
        Path audio = tempDir.resolve("cualquiera.ogg");
        Files.writeString(audio, "contenido irrelevante");

        AudioTranscriber withoutModel = new WhisperAudioTranscriber(
                "ffmpeg", tempDir.resolve("modelo-que-no-esta.bin"),
                Duration.ofSeconds(60), RUNNER);

        // La distinción importa: falta una herramienta del entorno, no sirve el
        // material del usuario. Un trabajo con este error debe reintentarse.
        assertThatThrownBy(() -> withoutModel.transcribe(audio))
                .isInstanceOf(TranscriptionException.class)
                .hasMessageContaining("modelo");
    }

    @Test
    @DisplayName("un archivo que no es audio se rechaza como material inválido")
    void garbageFileIsRejectedAsInvalidAudio() throws IOException {
        Assumptions.assumeTrue(modelAvailable(), "Modelo de transcripción no disponible");

        Path notAudio = tempDir.resolve("basura.ogg");
        Files.writeString(notAudio, "esto no es una grabación");

        assertThatThrownBy(() -> transcriber().transcribe(notAudio))
                .isInstanceOf(InvalidAudioException.class)
                .satisfies(thrown -> assertThat(((InvalidAudioException) thrown).reason())
                        .isEqualTo(InvalidAudioException.Reason.UNREADABLE));
    }

    @Test
    @DisplayName("un audio sin voz produce texto igualmente: whisper alucina")
    void audioWithoutSpeechStillProducesText() {
        Assumptions.assumeTrue(modelAvailable(), "Modelo de transcripción no disponible");

        // Este test documenta una limitación de la herramienta, no un requisito.
        // Ante un tono puro sin habla, whisper no devuelve texto vacío: inventa
        // contenido (se observó "[Música]" en pruebas manuales). Detectarlo por
        // heurísticas —descartar textos cortos o entre corchetes— descartaría
        // también transcripciones legítimas breves, que es un error peor.
        //
        // La salvaguarda no es técnica sino de proceso: la transcripción se muestra
        // en la pantalla de revisión y nada se publica sin confirmación humana.
        Path tone = toneAudio("tono.ogg", 5);

        Transcription transcription = transcriber().transcribe(tone);

        assertThat(transcription.text()).isNotNull();
        assertThat(transcription.audioDuration()).isPositive();
    }

    @Test
    @DisplayName("transcribe un audio con voz real")
    void transcribesRealSpeech() {
        Assumptions.assumeTrue(modelAvailable(), "Modelo de transcripción no disponible");

        String samplePath = System.getenv("OBRAVIVA_SAMPLE_AUDIO");
        Assumptions.assumeTrue(
                samplePath != null && !samplePath.isBlank()
                        && Files.isRegularFile(Path.of(samplePath)),
                "Audio de muestra no configurado: definí OBRAVIVA_SAMPLE_AUDIO");

        Transcription transcription = transcriber().transcribe(Path.of(samplePath));

        assertThat(transcription.text()).isNotBlank();
        assertThat(transcription.characterCount()).isGreaterThan(20);
        assertThat(transcription.processingTime()).isPositive();
        // Los acentos deben sobrevivir: el archivo se lee como UTF-8 explícito.
        assertThat(transcription.text()).doesNotContain("Ã");
    }

    private static boolean modelAvailable() {
        return Files.isRegularFile(modelPath());
    }

    private static boolean ffmpegAvailable() {
        try {
            return RUNNER.run(List.of("ffmpeg", "-version"), Duration.ofSeconds(20))
                    .exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}