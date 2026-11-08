package com.obraviva.platform.video;

import java.nio.file.Path;

/**
 * Resultado del preprocesamiento: el archivo normalizado listo para enviar al
 * servicio de reconstrucción, más los metadatos de origen y de salida para poder
 * explicarle al usuario qué se le hizo al video.
 */
public record TranscodeResult(
        Path outputFile,
        VideoMetadata sourceMetadata,
        VideoMetadata outputMetadata,
        boolean trimmed,
        boolean downscaled
) {

    /** Resumen en español, apto para el registro del trabajo o para la UI. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Video normalizado a ")
          .append(outputMetadata.width()).append("x").append(outputMetadata.height())
          .append(", ").append(String.format("%.1f", outputMetadata.durationSeconds())).append(" s, ")
          .append(String.format("%.1f", outputMetadata.megabytes())).append(" MB.");
        if (downscaled) {
            sb.append(" Se redujo la resolución de origen (")
              .append(sourceMetadata.width()).append("x").append(sourceMetadata.height()).append(").");
        }
        if (trimmed) {
            sb.append(" Se recortó la duración original de ")
              .append(String.format("%.1f", sourceMetadata.durationSeconds())).append(" s.");
        }
        return sb.toString();
    }
}