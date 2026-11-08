package com.obraviva.platform.captures3d;

/**
 * PENDING: se emitió URL de subida, archivo no verificado.
 * AVAILABLE: el archivo SPZ existe en el almacenamiento.
 * Los estados del pipeline de reconstrucción (PROCESSING, FAILED, etc.)
 * vivirán en processing_jobs, no acá.
 */
public enum CaptureStatus {
    PENDING,
    AVAILABLE
}