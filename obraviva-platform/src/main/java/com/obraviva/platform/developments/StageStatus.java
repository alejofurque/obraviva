package com.obraviva.platform.developments;

/**
 * Estado de una etapa de obra. DELAYED es un estado de primera
 * clase por decisión de producto: informar demoras construye
 * credibilidad, ocultarlas la destruye.
 */
public enum StageStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    DELAYED
}