package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Las versiones livianas de una foto, o de un fotograma de un video, con ffmpeg: miniatura
 * de 480 px del lado mayor y pantalla de 1600 px, en JPEG. Nunca agranda una imagen chica.
 *
 * <p>Se usa ffmpeg y no ImageIO porque ImageIO ignora la orientación EXIF (una foto de
 * celular en vertical saldría de costado) y no lee WebP; ffmpeg ya es una dependencia y
 * corrige la orientación al decodificar. {@code format=yuvj420p} al final del filtro evita
 * que el codificador JPEG rechace el rango de color limitado de los videos de celular.
 */
@Component
public class MediaVariantGenerator {

    static final int THUMB_SIDE = 480;
    static final int SCREEN_SIDE = 1600;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final String ffmpegPath;
    private final ProcessRunner processRunner;

    public MediaVariantGenerator(@Value("${obraviva.video.ffmpeg-path:ffmpeg}") String ffmpegPath, ProcessRunner processRunner) {
        this.ffmpegPath = ffmpegPath;
        this.processRunner = processRunner;
    }

    /** Las dos versiones generadas, listas para subir. */
    public record GeneratedVariants(Path thumb, Path screen) {
    }

    /**
     * Genera las dos versiones en {@code workDir}.
     *
     * @throws MediaVariantException si ffmpeg no pudo leer el archivo o no produjo imagen
     */
    public GeneratedVariants generate(Path original, MediaKind kind, Path workDir) {
        Path thumb = workDir.resolve("thumb.jpg");
        Path screen = workDir.resolve("screen.jpg");
        render(original, kind, THUMB_SIDE, thumb);
        render(original, kind, SCREEN_SIDE, screen);
        return new GeneratedVariants(thumb, screen);
    }

    private void render(Path original, MediaKind kind, int side, Path output) {
        if (kind == MediaKind.VIDEO) {
            // Un fotograma de un segundo adentro, que suele mostrar algo más que el arranque;
            // si el video dura menos, el primero.
            run(command(original, side, output, "1"));
            if (!hasContent(output)) {
                run(command(original, side, output, null));
            }
        } else {
            run(command(original, side, output, null));
        }
        if (!hasContent(output)) {
            throw new MediaVariantException("ffmpeg no produjo la versión de " + side + " px");
        }
    }

    private List<String> command(Path input, int side, Path output, String seekSeconds) {
        List<String> command = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y"));
        if (seekSeconds != null) {
            command.addAll(List.of("-ss", seekSeconds));
        }
        command.addAll(List.of(
                "-i", input.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(iw,%d)':'min(ih,%d)':force_original_aspect_ratio=decrease,format=yuvj420p".formatted(side, side),
                "-q:v", "3",
                output.toString()));
        return command;
    }

    private void run(List<String> command) {
        ProcessRunner.ProcessResult result = processRunner.run(command, TIMEOUT);
        if (result.failed() && !command.contains("-ss")) {
            throw new MediaVariantException("ffmpeg falló: " + result.stderrTail(300));
        }
    }

    private static boolean hasContent(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
