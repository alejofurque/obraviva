package com.obraviva.platform.video.ffmpeg;

import com.obraviva.platform.common.process.ProcessRunner;
import com.obraviva.platform.video.TranscodeResult;
import com.obraviva.platform.video.VideoConstraints;
import com.obraviva.platform.video.VideoMetadata;
import com.obraviva.platform.video.VideoProcessingException;
import com.obraviva.platform.video.VideoProcessor;
import com.obraviva.platform.video.VideoValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementación del preprocesamiento con ffmpeg/ffprobe como procesos externos.
 *
 * <p>Criterios:
 * <ul>
 *   <li>Se transcodifica siempre, aunque el origen ya cumpla los límites: lo que sale
 *       hacia el servicio de reconstrucción es siempre H.264 / yuv420p / MP4.</li>
 *   <li>El filtro de escala se aplica siempre, incluso cuando no hay nada que achicar.
 *       Con la caja orientada y {@code min()} sobre las dimensiones de entrada, el filtro
 *       es una operación nula para un video que ya entra, de modo que no hace falta una
 *       rama condicional. La garantía de que la salida encaja la da ffmpeg y no la
 *       aritmética de esta clase: cuando las dos decidían por separado, discrepaban.</li>
 *   <li>La caja respeta la orientación: 1920x1080 para material horizontal y 1080x1920
 *       para vertical. Sin esto, un 4K vertical de teléfono (2160x3840 efectivos) se
 *       reducía a 608x1080 en vez de 1080x1920 — pérdida grave de resolución justo en
 *       el caso de uso principal del producto.</li>
 *   <li>Nunca se amplía: {@code min(iw, ...)} y {@code min(ih, ...)} acotan la caja a las
 *       dimensiones reales del origen. Inventar píxeles no agrega información.</li>
 *   <li>Se descarta el audio y se limpian los metadatos del contenedor: no aportan a la
 *       reconstrucción y los videos de teléfono suelen traer geolocalización.</li>
 * </ul>
 *
 * <p>La rotación no se parsea a mano. Al insertar filtros, ffmpeg aplica la matriz de
 * rotación del contenedor y hornea el resultado en los píxeles; la salida ya no lleva
 * el metadato. Verificado con material real de iPhone (entrada 1024x576 con rotación
 * de -90 grados, salida 576x1024 sin Display Matrix y sin deformación).
 */
public class FfmpegVideoProcessor implements VideoProcessor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegVideoProcessor.class);

    private final String ffmpegPath;
    private final String ffprobePath;
    private final VideoConstraints constraints;
    private final ProcessRunner processRunner;

    public FfmpegVideoProcessor(String ffmpegPath,
                                String ffprobePath,
                                VideoConstraints constraints,
                                ProcessRunner processRunner) {
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.constraints = constraints;
        this.processRunner = processRunner;
    }

    @Override
    public VideoMetadata probe(Path source) {
        requireReadableFile(source);

        List<String> command = List.of(
                ffprobePath,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,codec_name:format=duration",
                "-of", "default=noprint_wrappers=1",
                source.toAbsolutePath().toString()
        );

        ProcessRunner.ProcessResult result = processRunner.run(command, constraints.probeTimeout());
        if (result.failed()) {
            throw new VideoValidationException(
                    VideoValidationException.Reason.UNREADABLE,
                    "No se pudo leer el archivo de video. Verificá que no esté dañado y que sea un formato de video válido.");
        }

        Map<String, String> values = parseKeyValues(result.stdout());

        Integer width = parseInteger(values.get("width"));
        Integer height = parseInteger(values.get("height"));
        if (width == null || height == null) {
            throw new VideoValidationException(
                    VideoValidationException.Reason.NO_VIDEO_STREAM,
                    "El archivo no contiene una pista de video. Subí el video de la filmación de la obra.");
        }

        Double duration = parseDouble(values.get("duration"));
        if (duration == null || duration <= 0) {
            throw new VideoValidationException(
                    VideoValidationException.Reason.UNREADABLE,
                    "No se pudo determinar la duración del video. Verificá que el archivo no esté dañado o incompleto.");
        }

        long sizeBytes = fileSize(source);
        String codec = values.getOrDefault("codec_name", "desconocido");

        return new VideoMetadata(width, height, duration, sizeBytes, codec);
    }

    @Override
    public TranscodeResult prepareForReconstruction(Path source, Path outputFile) {
        VideoMetadata sourceMetadata = probe(source);
        validate(sourceMetadata);

        createParentDirectories(outputFile);

        boolean trimmed = sourceMetadata.durationSeconds() > constraints.maxDurationSeconds();

        List<String> command = buildTranscodeCommand(source, outputFile);
        log.info("Preprocesando video {} ({}x{}, {} s, {}) -> {}",
                source.getFileName(), sourceMetadata.width(), sourceMetadata.height(),
                String.format("%.1f", sourceMetadata.durationSeconds()), sourceMetadata.videoCodec(),
                outputFile.getFileName());

        ProcessRunner.ProcessResult result = processRunner.run(command, constraints.transcodeTimeout());
        if (result.failed()) {
            throw new VideoProcessingException(
                    "ffmpeg terminó con código " + result.exitCode() + ": " + result.stderrTail(1000));
        }
        if (!Files.isRegularFile(outputFile) || fileSize(outputFile) == 0L) {
            throw new VideoProcessingException(
                    "ffmpeg terminó correctamente pero no generó un archivo de salida válido: " + outputFile);
        }

        VideoMetadata outputMetadata = probe(outputFile);

        // Verificación dura contra los límites del servicio externo: si esto falla, el
        // problema es nuestro (filtro mal armado, rotación inesperada) y no del usuario.
        if (!outputMetadata.fitsWithin(constraints.maxWidth(), constraints.maxHeight())) {
            throw new VideoProcessingException(
                    "El video transcodificado supera los límites de resolución permitidos ("
                            + outputMetadata.width() + "x" + outputMetadata.height() + ")");
        }
        if (outputMetadata.durationSeconds() > constraints.maxDurationSeconds() + 1) {
            throw new VideoProcessingException(
                    "El video transcodificado supera la duración máxima permitida ("
                            + String.format("%.1f", outputMetadata.durationSeconds()) + " s)");
        }

        boolean downscaled = wasDownscaled(sourceMetadata, outputMetadata);

        TranscodeResult transcodeResult =
                new TranscodeResult(outputFile, sourceMetadata, outputMetadata, trimmed, downscaled);
        log.info("Video preprocesado: {}", transcodeResult.summary());
        return transcodeResult;
    }

    private void validate(VideoMetadata metadata) {
        if (metadata.durationSeconds() < constraints.minDurationSeconds()) {
            throw new VideoValidationException(
                    VideoValidationException.Reason.TOO_SHORT,
                    "El video dura " + String.format("%.0f", metadata.durationSeconds())
                            + " segundos y no alcanza para reconstruir el ambiente. Filmá al menos "
                            + constraints.minDurationSeconds()
                            + " segundos recorriendo el espacio desde distintas posiciones.");
        }
        // La duración excedida no se rechaza: se recorta, según el documento maestro.
    }

    /**
     * Compara lado largo contra lado largo y corto contra corto. Un video rotado se
     * codifica horizontal y sale vertical con las mismas medidas intercambiadas; sin
     * esta comparación por lados se reportaría como reducido sin haber perdido nada.
     */
    private static boolean wasDownscaled(VideoMetadata source, VideoMetadata output) {
        int sourceLong = Math.max(source.width(), source.height());
        int sourceShort = Math.min(source.width(), source.height());
        int outputLong = Math.max(output.width(), output.height());
        int outputShort = Math.min(output.width(), output.height());
        return outputLong < sourceLong || outputShort < sourceShort;
    }

    /**
     * Caja de escalado orientada. Las comillas simples alrededor de cada expresión son
     * obligatorias a nivel del parser de ffmpeg, no del shell: las comas dentro de
     * {@code if()} y {@code min()} se interpretarían como separadores de filtros del
     * grafo. Cadena verificada empíricamente contra ffmpeg 9.0.1.
     */
    private String scaleFilter() {
        int maxLongSide = Math.max(constraints.maxWidth(), constraints.maxHeight());
        int maxShortSide = Math.min(constraints.maxWidth(), constraints.maxHeight());

        return "scale="
                + "w='if(gte(iw,ih),min(iw," + maxLongSide + "),min(iw," + maxShortSide + "))'"
                + ":h='if(gte(iw,ih),min(ih," + maxShortSide + "),min(ih," + maxLongSide + "))'"
                + ":force_original_aspect_ratio=decrease"
                + ":force_divisible_by=2";
    }

    private List<String> buildTranscodeCommand(Path source, Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-hide_banner");
        command.add("-nostdin");
        command.add("-loglevel");
        command.add("error");
        command.add("-i");
        command.add(source.toAbsolutePath().toString());

        // Sólo la primera pista de video; sin audio.
        command.add("-map");
        command.add("0:v:0");
        command.add("-an");

        command.add("-t");
        command.add(String.valueOf(constraints.maxDurationSeconds()));

        command.add("-vf");
        command.add(scaleFilter());

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("+faststart");

        // Limpieza de metadatos del contenedor (incluye geolocalización del teléfono).
        // La autorrotación ya fue aplicada por el pipeline de filtros.
        command.add("-map_metadata");
        command.add("-1");

        command.add(outputFile.toAbsolutePath().toString());
        return command;
    }

    private static Map<String, String> parseKeyValues(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        if (output == null) {
            return values;
        }
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                // La sección del stream se imprime antes que la del formato:
                // ante claves repetidas gana la primera.
                values.putIfAbsent(key, value);
            }
        }
        return values;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void requireReadableFile(Path source) {
        if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new VideoProcessingException("No existe o no se puede leer el archivo de video: " + source);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new VideoProcessingException("No se pudo determinar el tamaño del archivo: " + path, e);
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
            throw new VideoProcessingException("No se pudo crear el directorio de salida: " + parent, e);
        }
    }
}