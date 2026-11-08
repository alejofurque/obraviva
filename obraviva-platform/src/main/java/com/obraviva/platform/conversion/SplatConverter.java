package com.obraviva.platform.conversion;

import java.nio.file.Path;

/**
 * Puerto de conversión de formatos de Gaussian Splatting.
 *
 * <p>Trabaja sobre archivos locales, igual que {@code VideoProcessor}: no conoce
 * el almacenamiento ni la cola de trabajos.
 */
public interface SplatConverter {

    /**
     * Extrae el modelo PLY del archivo comprimido entregado por el servicio de
     * reconstrucción, escribiéndolo con un nombre canónico dentro de
     * {@code workDir}.
     *
     * @return la ruta del PLY extraído
     * @throws InvalidReconstructionArchiveException si el archivo no sirve
     * @throws SplatConversionException si falla técnicamente
     */
    Path extractModel(Path archive, Path workDir);

    /**
     * Convierte un PLY a SPZ con los parámetros verificados del proyecto.
     *
     * @throws SplatConversionException si falla la herramienta externa
     */
    ConversionResult convertToSpz(Path plyFile, Path outputFile);
}