package com.obraviva.platform.conversion;

import java.nio.file.Path;

/**
 * Resultado de la conversión, con los tamaños de origen y destino para poder
 * registrar la compresión obtenida.
 */
public record ConversionResult(
        Path outputFile,
        long sourceSizeBytes,
        long outputSizeBytes) {

    /** Factor de compresión, por ejemplo 14,6 para una reducción a la catorceava parte. */
    public double compressionRatio() {
        return outputSizeBytes == 0 ? 0 : (double) sourceSizeBytes / outputSizeBytes;
    }

    public String summary() {
        return String.format(
                "PLY de %.1f MB convertido a SPZ de %.1f MB (%.1fx de compresión)",
                sourceSizeBytes / (1024.0 * 1024.0),
                outputSizeBytes / (1024.0 * 1024.0),
                compressionRatio());
    }
}