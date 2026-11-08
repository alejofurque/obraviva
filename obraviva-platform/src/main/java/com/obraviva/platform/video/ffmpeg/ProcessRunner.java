package com.obraviva.platform.video.ffmpeg;

import com.obraviva.platform.video.VideoProcessingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ejecuta un proceso externo con timeout y captura de salidas.
 *
 * <p>Las salidas se redirigen a archivos temporales en vez de leerse desde los pipes:
 * ffmpeg escribe mucho en stderr y un pipe sin drenar bloquea el proceso hijo de forma
 * indefinida. Con redirección a archivo el problema no existe y el timeout funciona.
 */
public class ProcessRunner {

    public record ProcessResult(int exitCode, String stdout, String stderr) {

        public boolean failed() {
            return exitCode != 0;
        }

        /** Últimas líneas de stderr, para mensajes de error acotados. */
        public String stderrTail(int maxChars) {
            String trimmed = stderr == null ? "" : stderr.trim();
            if (trimmed.length() <= maxChars) {
                return trimmed;
            }
            return "..." + trimmed.substring(trimmed.length() - maxChars);
        }
    }

    public ProcessResult run(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("El comando no puede estar vacío");
        }

        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            stdoutFile = Files.createTempFile("obraviva-proc-out-", ".log");
            stderrFile = Files.createTempFile("obraviva-proc-err-", ".log");

            Process process = new ProcessBuilder(command)
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new VideoProcessingException(
                        "El proceso externo '" + command.getFirst() + "' superó el tiempo máximo de "
                                + timeout.toSeconds() + " s y fue interrumpido");
            }

            return new ProcessResult(process.exitValue(), readAll(stdoutFile), readAll(stderrFile));

        } catch (IOException e) {
            throw new VideoProcessingException(
                    "No se pudo ejecutar '" + command.getFirst()
                            + "'. Verificá que esté instalado y accesible desde el PATH del proceso.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoProcessingException(
                    "La ejecución de '" + command.getFirst() + "' fue interrumpida", e);
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    private static String readAll(Path path) throws IOException {
        // Sin readString: ffmpeg puede emitir bytes no UTF-8 y no queremos que eso rompa el parseo.
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Archivo temporal huérfano: no justifica fallar la operación.
        }
    }
}