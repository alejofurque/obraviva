package com.obraviva.platform.organizations;

import java.time.Instant;
import java.util.UUID;

public final class OrganizationMemberDtos {

    private OrganizationMemberDtos() {
    }

    public record OrganizationMemberResponse(
            UUID id,
            UUID userId,
            String email,
            String fullName,
            OrganizationRole role,
            Instant createdAt) {
    }

    /**
     * Respuesta del alta. {@code initialPassword} sólo viene si la cuenta se creó en
     * esta operación, y no se puede volver a consultar: el dueño la anota y se la
     * comunica a la persona.
     */
    public record AddedOrganizationMemberResponse(
            UUID id,
            UUID userId,
            String email,
            String fullName,
            OrganizationRole role,
            Instant createdAt,
            boolean accountCreated,
            String initialPassword) {
    }
}
