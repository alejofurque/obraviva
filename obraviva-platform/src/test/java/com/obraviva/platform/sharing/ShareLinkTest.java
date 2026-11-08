package com.obraviva.platform.sharing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShareLinkTest {

    private ShareLink linkExpiringAt(Instant expiresAt) {
        return new ShareLink(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ShareToken.hash(ShareToken.generate()),
                "familia",
                expiresAt,
                UUID.randomUUID());
    }

    @Test
    @DisplayName("un enlace sin vencimiento sirve mientras no se revoque")
    void linkWithoutExpiryStaysUsable() {
        ShareLink link = linkExpiringAt(null);

        assertThat(link.isUsableAt(Instant.now())).isTrue();
        assertThat(link.isUsableAt(Instant.now().plus(Duration.ofDays(3650)))).isTrue();
    }

    @Test
    @DisplayName("un enlace vencido deja de servir sin que nadie haga nada")
    void expiredLinkStopsWorking() {
        Instant expiry = Instant.now().plus(Duration.ofDays(7));
        ShareLink link = linkExpiringAt(expiry);

        assertThat(link.isUsableAt(expiry.minus(Duration.ofSeconds(1)))).isTrue();
        assertThat(link.isUsableAt(expiry.plus(Duration.ofSeconds(1)))).isFalse();
        // Vencido no es revocado: nadie lo cortó.
        assertThat(link.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("la revocación corta el acceso y deja quién y cuándo")
    void revocationRecordsAuthorAndMoment() {
        ShareLink link = linkExpiringAt(null);
        UUID revoker = UUID.randomUUID();

        link.revoke(revoker);

        assertThat(link.isUsableAt(Instant.now())).isFalse();
        assertThat(link.isRevoked()).isTrue();
        assertThat(link.getRevokedBy()).isEqualTo(revoker);
        assertThat(link.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("revocar dos veces conserva la revocación que cortó el acceso")
    void secondRevocationIsIgnored() {
        ShareLink link = linkExpiringAt(null);
        UUID first = UUID.randomUUID();
        link.revoke(first);
        Instant firstMoment = link.getRevokedAt();

        link.revoke(UUID.randomUUID());

        assertThat(link.getRevokedBy()).isEqualTo(first);
        assertThat(link.getRevokedAt()).isEqualTo(firstMoment);
    }

    @Test
    @DisplayName("cada visita queda contada")
    void accessIsCounted() {
        ShareLink link = linkExpiringAt(null);

        link.registerAccess();
        link.registerAccess();

        assertThat(link.getAccessCount()).isEqualTo(2);
        assertThat(link.getLastAccessedAt()).isNotNull();
    }
}