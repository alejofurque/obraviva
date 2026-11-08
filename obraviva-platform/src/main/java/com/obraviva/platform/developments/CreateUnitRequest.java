package com.obraviva.platform.developments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de una casa. {@code withStandardStages} carga las etapas estándar de una casa; sin
 * el campo no se cargan, que es como se comportaba el alta antes de existir la plantilla.
 */
public record CreateUnitRequest(
        @NotBlank @Size(max = 50) String code,
        @Size(max = 300) String description,
        Boolean withStandardStages) {

    public CreateUnitRequest(String code, String description) {
        this(code, description, null);
    }

    public boolean shouldApplyStandardStages() {
        return Boolean.TRUE.equals(withStandardStages);
    }
}
