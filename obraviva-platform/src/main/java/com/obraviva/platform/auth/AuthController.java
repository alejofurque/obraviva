package com.obraviva.platform.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public Map<String, String> me(@AuthenticationPrincipal Jwt jwt) {
        User user = userRepository.findById(userId(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return Map.of(
                "id", user.getId().toString(),
                "email", user.getEmail(),
                "fullName", user.getFullName());
    }

    @PostMapping("/password")
    public LoginResponse changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(userId(jwt), request);
    }

    @PostMapping("/refresh")
public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request);
}

@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logout(@Valid @RequestBody RefreshRequest request) {
    authService.logout(request);
}
}