package com.obraviva.platform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${obraviva.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.repository = repository;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /** Emite un refresh token nuevo y devuelve su valor en claro (solo viaja una vez). */
    public String issue(UUID userId) {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, hash(rawToken), Instant.now().plus(refreshTokenTtl)));
        return rawToken;
    }

    /** Valida, revoca el token usado (rotación) y devuelve el usuario dueño. */
    @Transactional
    public UUID validateAndRevoke(String rawToken) {
        RefreshToken token = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalidToken);

        if (!token.isActive(Instant.now())) {
            throw invalidToken();
        }

        token.revoke(Instant.now());
        return token.getUserId();
    }

    /** Logout: revoca si existe y está activo; silencioso si no (idempotente). */
    @Transactional
    public void revokeIfPresent(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .filter(t -> t.isActive(Instant.now()))
                .ifPresent(t -> t.revoke(Instant.now()));
    }

    /**
     * Cierra todas las sesiones del usuario. Se usa al cambiar o reiniciar una
     * contraseña: quien la conocía antes no puede seguir renovando su acceso.
     */
    @Transactional
    public void revokeAllFor(UUID userId) {
        Instant now = Instant.now();
        repository.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
    }
}