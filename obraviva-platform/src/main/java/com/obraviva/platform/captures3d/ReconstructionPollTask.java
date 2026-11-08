package com.obraviva.platform.captures3d;

import java.time.Duration;
import java.util.UUID;

/**
 * Datos para consultar el estado de una reconstrucción en curso.
 *
 * <p>{@code timeInStep} viaja con la tarea porque el corte por tiempo se decide fuera
 * de la transacción, junto al resto de la lógica del worker.
 */
public record ReconstructionPollTask(
        UUID jobId,
        UUID captureId,
        String externalReference,
        Duration timeInStep) {
}