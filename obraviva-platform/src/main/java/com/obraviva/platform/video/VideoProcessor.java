package com.obraviva.platform.video;

import java.nio.file.Path;

/**
 * Puerto de preprocesamiento de video del pipeline 3D.
 *
 * <p>Trabaja exclusivamente sobre archivos locales: no conoce MinIO, ni la cola de
 * trabajos, ni las entidades de captura. Quien lo usa se encarga de traer el archivo
 * al disco y de llevarse el resultado.
 */
public interface VideoProcessor {

    /**
     * Lee los metadatos del video.
     *
     * @throws VideoValidationException si el archivo no tiene pista de video o no es legible
     * @throws VideoProcessingException si falla la herramienta externa
     */
    VideoMetadata probe(Path source);

    /**
     * Valida el video y lo normaliza a los límites del servicio de reconstrucción,
     * escribiendo el resultado en {@code outputFile}.
     *
     * @throws VideoValidationException si el video debe rechazarse (mensaje apto para el usuario)
     * @throws VideoProcessingException si falla la herramienta externa
     */
    TranscodeResult prepareForReconstruction(Path source, Path outputFile);
}