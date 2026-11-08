package com.obraviva.platform.ai.extraction;

/**
 * Confianza declarada por el modelo sobre un elemento de la propuesta.
 *
 * <p>Es un nivel y no un número a propósito. Un valor como 0,87 sugeriría una
 * precisión que no existe: los modelos de lenguaje no calculan probabilidades
 * calibradas, las enuncian. Además chocaría con el principio del documento maestro
 * de que ninguna cifra del sistema proviene de un modelo de lenguaje.
 *
 * <p>Su función es operativa: indicarle al revisor dónde mirar con más atención,
 * no cuantificar nada.
 */
public enum ConfidenceLevel {

    /** El audio lo dice de forma explícita. */
    HIGH,

    /** Se desprende del audio, pero admite otra lectura. */
    MEDIUM,

    /** Interpretación dudosa: el revisor debería verificarla. */
    LOW;

    public static ConfidenceLevel fromText(String raw) {
        if (raw == null) {
            return LOW;
        }
        return switch (raw.trim().toUpperCase()) {
            case "HIGH", "ALTA" -> HIGH;
            case "MEDIUM", "MEDIA" -> MEDIUM;
            // Cualquier valor inesperado se degrada a la confianza más baja: ante la
            // duda sobre lo que dijo el modelo, que el revisor lo mire.
            default -> LOW;
        };
    }
}