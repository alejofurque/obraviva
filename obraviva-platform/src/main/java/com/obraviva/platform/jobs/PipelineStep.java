package com.obraviva.platform.jobs;

/**
 * Paso del pipeline dentro de un trabajo asincrónico.
 *
 * <p>Complementa a {@link JobStatus}, que responde "¿está trabajando, terminó o
 * falló?". Este enum responde "¿en qué punto del proceso está?". Un trabajo en
 * {@code PROCESSING} puede estar preprocesando un video, esperando al servicio
 * externo o transcribiendo un audio, y el worker necesita distinguirlos para saber
 * qué hacer en cada ciclo.
 *
 * <p>El enum abarca tres pipelines distintos —reconstrucción 3D, interpretación de
 * audio y generación de reportes— con {@code DONE} como paso final común y
 * {@code AWAIT_REVIEW} compartido por los dos que exigen confirmación humana. Se
 * mantienen en un solo enum porque comparten la misma tabla y la misma mecánica de
 * reclamo; cada worker declara qué pasos sabe ejecutar y no toca los de los otros.
 *
 * <p>Las transiciones válidas se declaran acá: un paso no puede saltearse.
 */
public enum PipelineStep {

    // --- Pipeline de reconstrucción 3D ---

    /** Validar y normalizar el video con ffmpeg. */
    PREPROCESS,

    /** Enviar el video normalizado al servicio externo. Consume un crédito. */
    SUBMIT,

    /** Esperar a que el servicio externo termine de calcular. */
    AWAIT_EXTERNAL,

    /** Descargar el resultado comprimido antes de que venza. */
    DOWNLOAD,

    /** Descomprimir, extraer el PLY y convertirlo a SPZ. */
    CONVERT,

    // --- Pipeline de interpretación de audio ---

    /** Transcribir el audio a texto con whisper, en la propia máquina. */
    TRANSCRIBE,

    /** Interpretar la transcripción con el modelo de lenguaje. */
    INTERPRET,

    // --- Pipeline de generación de reportes ---

    /**
     * Redactar el reporte del período a partir del snapshot ya compuesto. La
     * composición de los datos no es un paso de la cola: ocurre de forma sincrónica
     * al crear el reporte, porque son consultas a la base y no I/O externo, y porque
     * permite rechazar un período vacío antes de encolar nada.
     */
    NARRATE,

    // --- Común a los pipelines con revisión humana ---

    /**
     * Esperar la confirmación humana. Es el único paso que no avanza solo: lo
     * resuelve una persona desde la pantalla de revisión.
     */
    AWAIT_REVIEW,

    // --- Común a todos ---

    /** Pipeline terminado. */
    DONE;

    public boolean canAdvanceTo(PipelineStep target) {
        return switch (this) {
            case PREPROCESS -> target == SUBMIT;
            case SUBMIT -> target == AWAIT_EXTERNAL;
            case AWAIT_EXTERNAL -> target == DOWNLOAD;
            case DOWNLOAD -> target == CONVERT;
            case CONVERT -> target == DONE;
            case TRANSCRIBE -> target == INTERPRET;
            case INTERPRET -> target == AWAIT_REVIEW;
            case NARRATE -> target == AWAIT_REVIEW;
            case AWAIT_REVIEW -> target == DONE;
            case DONE -> false;
        };
    }

    /** Paso inicial según el tipo de trabajo. */
    public static PipelineStep initialFor(JobType jobType) {
        return switch (jobType) {
            case CAPTURE_3D_RECONSTRUCTION -> PREPROCESS;
            case AUDIO_EXTRACTION -> TRANSCRIBE;
            case REPORT_GENERATION -> NARRATE;
        };
    }
}