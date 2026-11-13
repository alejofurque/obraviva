package com.obraviva.platform.ai.transcription;

import com.obraviva.platform.common.process.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Transcribe audio a texto con el filtro whisper de ffmpeg, que implementa
 * whisper.cpp y corre enteramente en la máquina local.
 *
 * <p><strong>Parámetros verificados empíricamente</strong> con audio real de
 * WhatsApp (Opus, 48 kHz mono, ~20 kb/s):
 * <ul>
 *   <li>Modelo {@code small}. El {@code base} produjo una transcripción inservible
 *       para el español: perdía términos de construcción, fechas y hasta el sentido
 *       de frases enteras. Con {@code small} la información necesaria para la
 *       extracción aparece completa.</li>
 *   <li>{@code queue=30} — el filtro trocea el audio en ventanas y transcribe cada
 *       una por separado. El valor por omisión de 3 segundos le quita a whisper el
 *       contexto de la frase, que es justamente lo que usa para desambiguar, y los
 *       errores se concentran en los bordes de cada ventana.</li>
 *   <li>{@code language=es} fijo, nunca {@code auto}. Con audio degradado el
 *       detector automático puede confundir el español con lenguas próximas.</li>
 *   <li>{@code -ar 16000 -ac 1} — whisper.cpp espera 16 kHz monoaural.</li>
 * </ul>
 *
 * <p>Rendimiento medido: 7,7 veces más rápido que el tiempo real, de modo que un
 * audio de tres minutos se transcribe en unos veinticinco segundos.
 */
public class WhisperAudioTranscriber implements AudioTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperAudioTranscriber.class);

    private static final String LANGUAGE = "es";
    private static final String QUEUE_SECONDS = "30";
    private static final String SAMPLE_RATE = "16000";
    private static final String CHANNELS = "1";

    private final String ffmpegPath;
    private final Path modelFile;
    private final Duration timeout;
    private final ProcessRunner processRunner;

    public WhisperAudioTranscriber(
            String ffmpegPath,
            Path modelFile,
            Duration timeout,
            ProcessRunner processRunner) {
        this.ffmpegPath = ffmpegPath;
        this.modelFile = modelFile;
        this.timeout = timeout;
        this.processRunner = processRunner;
    }

    @Override
    public Transcription transcribe(Path audioFile) {
        requireReadable(audioFile, "audio");
        requireReadable(modelFile, "modelo de transcripción");

        Path output = null;
        Instant start = Instant.now();
        try {
            output = Files.createTempFile("obraviva-transcript-", ".txt");
            // El filtro escribe el archivo; si ya existe puede confundirlo.
            Files.deleteIfExists(output);

            List<String> command = buildCommand(audioFile, output);
            log.info("Transcribiendo {} con el modelo {}",
                    audioFile.getFileName(), modelFile.getFileName());

            ProcessRunner.ProcessResult result = processRunner.run(command, timeout);
            if (result.failed()) {
                throw interpretFailure(result);
            }

            String text = readTranscript(output);
            Duration processingTime = Duration.between(start, Instant.now());

            if (text.isBlank()) {
                throw noSpeechDetected();
            }

            Transcription transcription = new Transcription(
                    text, audioDuration(audioFile), processingTime);
            log.info("Transcripción completada: {}", transcription.summary());
            return transcription;

        } catch (IOException e) {
            throw new TranscriptionException(
                    "Error de disco durante la transcripción de " + audioFile, e);
        } finally {
            deleteQuietly(output);
        }
    }

    private List<String> buildCommand(Path audioFile, Path output) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-hide_banner");
        command.add("-nostdin");
        command.add("-loglevel");
        command.add("error");
        command.add("-i");
        command.add(audioFile.toAbsolutePath().toString());

        // whisper.cpp espera 16 kHz monoaural.
        command.add("-ar");
        command.add(SAMPLE_RATE);
        command.add("-ac");
        command.add(CHANNELS);

        command.add("-af");
        command.add("whisper="
                + "model=" + escapeFilterValue(modelFile.toAbsolutePath().toString())
                + ":language=" + LANGUAGE
                + ":queue=" + QUEUE_SECONDS
                + ":destination=" + escapeFilterValue(output.toAbsolutePath().toString())
                + ":format=text");

        // Sólo interesa el archivo de texto que escribe el filtro, no el audio de salida.
        command.add("-f");
        command.add("null");
        command.add("-");
        return command;
    }

    /**
     * Adecua una ruta de Windows a la sintaxis de las descripciones de filtro.
     *
     * <p>Hay <strong>dos niveles de parseo</strong> encadenados: el del grafo de
     * filtros y el de las opciones de cada filtro. Cada uno consume un nivel de
     * escapes, de modo que un solo {@code \} se gasta en el primero y al segundo le
     * llega un {@code :} pelado que interpreta como separador de opciones. Hacen
     * falta dos barras invertidas para que sobreviva una.
     *
     * <p>En el literal de Java se escriben cuatro porque el compilador consume otro
     * nivel más. Verificado empíricamente: con una sola barra ffmpeg falla con
     * "No option name near"; con dos, transcribe correctamente.
     *
     * <p>Las barras invertidas de la ruta se convierten en barras normales, que
     * ffmpeg acepta en Windows y que evitan un tercer conflicto de escapes.
     */
    private static String escapeFilterValue(String path) {
        return path.replace('\\', '/').replace(":", "\\\\:");
    }

    /**
     * El texto se lee siempre como UTF-8, nunca con la codificación por omisión de
     * la plataforma: el archivo que escribe ffmpeg está en UTF-8 y en español los
     * acentos aparecen en casi todas las frases.
     */
    private static String readTranscript(Path output) throws IOException {
        if (!Files.isRegularFile(output)) {
            // El filtro no escribe archivo cuando no reconoce ningún segmento de voz.
            return "";
        }
        String raw = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        // El filtro emite un renglón por segmento; se unen en un texto corrido.
        return raw.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + " " + b)
                .orElse("")
                .trim();
    }

    private static InvalidAudioException noSpeechDetected() {
        return new InvalidAudioException(
                InvalidAudioException.Reason.NO_SPEECH_DETECTED,
                "No se reconoció ninguna voz en el audio. Verificá que la grabación "
                        + "tenga sonido y que se escuche con claridad.");
    }

    /**
     * Un fallo sobre un archivo ya verificado como legible se debe al contenido del
     * audio, no al entorno. Mismo criterio adoptado para gsbox: los mensajes de las
     * herramientas externas cambian entre versiones y perseguirlos con listas de
     * cadenas es una carrera perdida.
     */
    private static RuntimeException interpretFailure(ProcessRunner.ProcessResult result) {
        String detail = result.stderrTail(1000);
        log.warn("ffmpeg no pudo transcribir el audio. Salida: {}", detail);
        return new InvalidAudioException(
                InvalidAudioException.Reason.UNREADABLE,
                "No se pudo procesar el audio. Verificá que el archivo no esté dañado y "
                        + "que sea una grabación de voz válida.");
    }

    /**
     * Duración del audio, para el registro. Si no se puede determinar se devuelve
     * cero: es un dato informativo y no justifica hacer fallar la transcripción.
     */
    private Duration audioDuration(Path audioFile) {
        try {
            ProcessRunner.ProcessResult result = processRunner.run(List.of(
                    ffmpegPath.replace("ffmpeg", "ffprobe"),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audioFile.toAbsolutePath().toString()
            ), Duration.ofSeconds(30));

            if (result.failed()) {
                return Duration.ZERO;
            }
            String raw = result.stdout().trim();
            return Duration.ofMillis((long) (Double.parseDouble(raw) * 1000));

        } catch (RuntimeException e) {
            return Duration.ZERO;
        }
    }

    private static void requireReadable(Path path, String description) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new TranscriptionException(
                    "No existe o no se puede leer el " + description + ": " + path);
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