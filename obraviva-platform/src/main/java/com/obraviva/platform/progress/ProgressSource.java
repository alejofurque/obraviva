package com.obraviva.platform.progress;

/**
 * Origen de la entrada de avance. AUDIO existe desde el modelo inicial
 * para el pipeline de IA (interpretación de audio del encargado), que
 * publicará entradas solo tras confirmación humana.
 *
 * <p>INITIAL_LOAD ("Carga inicial") es el trabajo hecho antes de empezar el
 * seguimiento de una obra, cargado de una vez y con fechas aproximadas. No se crea
 * por la API: lo crea el script de carga de la obra. Una corrección conserva el
 * origen. No es novedad (queda fuera de Novedades y de "sin ver") y el comprador lo
 * ve aparte, como "Antes del seguimiento", con mes y año.
 */
public enum ProgressSource {
    MANUAL,
    AUDIO,
    INITIAL_LOAD
}
