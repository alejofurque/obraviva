package com.obraviva.platform.developments;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class UnitMemberDtos {

    private UnitMemberDtos() {
    }

    /**
     * Alta por correo electrónico: el desarrollista escribe el correo del comprador,
     * no un identificador. Si el correo no tiene cuenta, el nombre permite crearla;
     * sin nombre, la vinculación exige una cuenta existente.
     */
    public record AddUnitMemberRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 200) String fullName,
            /*
             * Contraseña que elige el dueño para una cuenta nueva. Opcional: vacía o ausente,
             * se genera una. Si el correo ya tiene cuenta, se ignora. El mínimo lo valida
             * el servicio, con un mensaje que se puede mostrar.
             */
            String password) {

        public AddUnitMemberRequest {
            password = blankToNull(password);
        }

        public AddUnitMemberRequest(String email, String fullName) {
            this(email, fullName, null);
        }

        public AddUnitMemberRequest(String email) {
            this(email, null, null);
        }
    }

    /**
     * Nueva contraseña para un comprador. Sin cuerpo, vacía o ausente, se genera una y se
     * devuelve una sola vez; escrita, no vuelve en la respuesta.
     */
    public record SetPasswordRequest(String password) {

        public SetPasswordRequest {
            password = blankToNull(password);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Respuesta del alta. {@code initialPassword} sólo viene si la cuenta se creó en
     * esta operación, y no se puede volver a consultar.
     */
    public record AddedUnitMemberResponse(
            UUID id,
            UUID unitId,
            UUID userId,
            String email,
            String fullName,
            Instant createdAt,
            boolean accountCreated,
            String initialPassword) {
    }

    public record UnitMemberResponse(
            UUID id,
            UUID unitId,
            UUID userId,
            String email,
            String fullName,
            Instant createdAt) {
    }
}