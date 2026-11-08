package com.obraviva.platform.organizations;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de un encargado. El nombre sólo hace falta si el correo todavía no tiene
 * cuenta: con él se crea.
 */
public record AddOrganizationMemberRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 200) String fullName) {
}
