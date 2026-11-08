package com.obraviva.platform.sharing;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ShareLinkDtos {

    private ShareLinkDtos() {
    }

    public record CreateShareLinkRequest(
            @Size(max = 120) String label,
            /** Opcional. Nulo significa que el enlace no vence por tiempo. */
            Instant expiresAt) {
    }

    /**
     * Respuesta de la emisión. <strong>Es la única vez que existe el token.</strong>
     * Después queda sólo su hash, y ni la base ni la aplicación pueden reconstruirlo.
     */
    public record CreatedShareLinkResponse(
            UUID id,
            String token,
            String label,
            Instant expiresAt,
            Instant createdAt) {
    }

    /** Listado para el emisor. Nunca incluye el token ni su hash. */
    public record ShareLinkSummaryResponse(
            UUID id,
            String label,
            boolean active,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastAccessedAt,
            int accessCount,
            Instant createdAt,
            /** Quién lo creó, para el dueño; nulo para el comprador, que no ve nombres del equipo. */
            String createdByName,
            boolean createdByYou) {
    }
}