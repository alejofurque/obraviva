package com.obraviva.platform.ai.extraction;

/**
 * Puerto de interpretación de un parte de obra narrado.
 *
 * <p>A diferencia de la transcripción, acá sí hay abstracción con varias
 * implementaciones: el documento maestro exige que el proveedor de modelo de
 * lenguaje sea intercambiable, y el desarrollo diario corre contra un adaptador
 * simulado que no consume cuota.
 */
public interface ProgressExtractor {

    /**
     * Interpreta la transcripción y propone actualizaciones.
     *
     * <p>La propuesta devuelta no está validada: puede referenciar etapas
     * inexistentes o estados inválidos. Depurarla es responsabilidad de
     * {@link ExtractionValidator}.
     *
     * @throws ExtractionException si falla la comunicación con el proveedor
     */
    AudioExtraction extract(ExtractionRequest request);
}