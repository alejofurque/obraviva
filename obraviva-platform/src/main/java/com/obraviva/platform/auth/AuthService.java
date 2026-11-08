package com.obraviva.platform.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository
                .findByEmail(AccountProvisioningService.normalizeEmail(request.email()))
                .orElseThrow(AuthService::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        return issueTokens(user);
    }

    public LoginResponse refresh(RefreshRequest request) {
        var userId = refreshTokenService.validateAndRevoke(request.refreshToken());
        User user = userRepository.findById(userId)
                .orElseThrow(AuthService::invalidCredentials);
        return issueTokens(user);
    }

    public void logout(RefreshRequest request) {
        refreshTokenService.revokeIfPresent(request.refreshToken());
    }

    /**
     * Cambio de contraseña de la propia cuenta.
     *
     * <p>Cierra todas las sesiones y devuelve tokens nuevos, así quien cambió la
     * contraseña sigue adentro y cualquier otra sesión abierta con la anterior se corta.
     *
     * <p>Una contraseña actual incorrecta responde 400 y no 401: el cliente interpreta
     * un 401 como sesión vencida, intentaría renovarla y terminaría cerrándola.
     */
    @Transactional
    public LoginResponse changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La contraseña nueva tiene que ser distinta de la actual");
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenService.revokeAllFor(userId);
        return issueTokens(user);
    }

    private LoginResponse issueTokens(User user) {
        return new LoginResponse(
                jwtTokenService.generateAccessToken(user),
                refreshTokenService.issue(user.getId()));
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }
}