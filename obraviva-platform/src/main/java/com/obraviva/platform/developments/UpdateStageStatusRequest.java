package com.obraviva.platform.developments;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Cambio de estado. {@code effectiveOn} es el día en que pasó, para cargar "la etapa
 * terminó el viernes"; sin fecha, hoy. No puede ser futura.
 */
public record UpdateStageStatusRequest(@NotNull StageStatus status, LocalDate effectiveOn) {

    public UpdateStageStatusRequest(StageStatus status) {
        this(status, null);
    }
}
