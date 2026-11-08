package com.obraviva.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambio de la propia contraseña. La nueva pide un mínimo de ocho caracteres: la
 * inicial que genera el sistema tiene doce, y la que elige la persona no debería ser
 * más débil que esa.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword) {
}
