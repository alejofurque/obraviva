package com.obraviva.platform.developments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Alta de un desarrollo. Sin tipo, es con unidades: así se comportaba antes de existir el
 * tipo, y un cliente que no lo mande no cambia de comportamiento. La interfaz lo manda
 * siempre, con obra única como opción por omisión.
 */
public record CreateDevelopmentRequest(
        @NotNull UUID organizationId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 300) String address,
        DevelopmentKind kind) {

    public CreateDevelopmentRequest(UUID organizationId, String name, String address) {
        this(organizationId, name, address, null);
    }

    public DevelopmentKind kindOrDefault() {
        return kind == null ? DevelopmentKind.MULTI_UNIT : kind;
    }
}