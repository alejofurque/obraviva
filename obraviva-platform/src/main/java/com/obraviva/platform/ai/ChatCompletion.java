package com.obraviva.platform.ai;

/**
 * Superficie mínima contra el proveedor de modelo de lenguaje: entra un prompt,
 * sale texto.
 *
 * <p>Existe para aislar la API de Spring AI en un único punto. Los nombres de sus
 * clases y métodos cambiaron entre versiones mayores, y concentrar el contacto en
 * una lambda de la configuración permite corregir un cambio de API en un solo lugar
 * en vez de a lo largo de los adaptadores.
 *
 * <p>Vive en la raíz del módulo de inteligencia artificial, y no dentro del paquete
 * de extracción, porque es <strong>API pública del módulo</strong>: la usan tanto la
 * interpretación de audios como la redacción de reportes. Un módulo que dependiera
 * de {@code ai.extraction.gemini} estaría atado a un detalle interno de otro
 * pipeline.
 *
 * <p>El beneficio secundario es de pruebas: los adaptadores se ejercitan con
 * respuestas preparadas, sin red, sin contexto de Spring y sin consumir cuota. Lo que
 * se prueba es el armado del prompt y el análisis de la respuesta, que es donde se
 * concentran los errores reales.
 */
@FunctionalInterface
public interface ChatCompletion {

    /**
     * @return la respuesta del modelo como texto crudo
     * @throws RuntimeException si falla la comunicación con el proveedor
     */
    String complete(String prompt);
}