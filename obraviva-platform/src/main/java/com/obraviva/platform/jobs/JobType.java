package com.obraviva.platform.jobs;

public enum JobType {

    /** Video de obra a captura 3D navegable: ffmpeg, servicio externo, gsbox. */
    CAPTURE_3D_RECONSTRUCTION,

    /** Audio del encargado a propuesta estructurada: transcripción e interpretación. */
    AUDIO_EXTRACTION,

    /**
     * Datos del período a reporte redactado. La composición del snapshot ocurre
     * antes, de forma sincrónica; lo que corre por la cola es la redacción, que
     * depende de un servicio externo.
     */
    REPORT_GENERATION
}