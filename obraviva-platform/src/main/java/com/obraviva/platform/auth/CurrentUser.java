package com.obraviva.platform.auth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Quién hace el pedido: el {@code sub} del token de acceso es el id de su cuenta. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
