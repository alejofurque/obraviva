package com.obraviva.platform.media;

/**
 * Las versiones livianas de un medio: miniatura y pantalla en JPEG.
 *
 * <p>PENDING: en la cola del worker. READY: las dos claves están. FAILED: se agotaron los
 * intentos; se sigue viendo con el original. SKIPPED: no se achica (un SVG es vectorial y
 * liviano).
 */
public enum MediaVariantsStatus {
    PENDING,
    READY,
    FAILED,
    SKIPPED
}
