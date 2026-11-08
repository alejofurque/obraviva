package com.obraviva.platform.video;

/**
 * Metadatos relevantes de un archivo de video, obtenidos con ffprobe.
 *
 * <p>Nota sobre {@code width} y {@code height}: son las dimensiones codificadas del
 * stream, sin aplicar la matriz de rotación del contenedor. Un video vertical de
 * iPhone se reporta como 1920x1080 con rotación -90. Por eso las validaciones de
 * resolución se hacen sobre la salida transcodificada, donde ffmpeg ya aplicó la
 * autorrotación, y no sobre la entrada.
 */
public record VideoMetadata(
        int width,
        int height,
        double durationSeconds,
        long sizeBytes,
        String videoCodec
) {

    /**
     * Verifica si el video entra en la caja indicada, sin asumir orientación:
     * compara el lado largo contra el límite largo y el corto contra el corto.
     */
    public boolean fitsWithin(int maxWidth, int maxHeight) {
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        int maxLongSide = Math.max(maxWidth, maxHeight);
        int maxShortSide = Math.min(maxWidth, maxHeight);
        return longSide <= maxLongSide && shortSide <= maxShortSide;
    }

    public double megabytes() {
        return sizeBytes / (1024.0 * 1024.0);
    }
}