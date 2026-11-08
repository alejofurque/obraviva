package com.obraviva.platform.developments;

import com.obraviva.platform.developments.AccountResetService.ResetPasswordResponse;
import com.obraviva.platform.developments.UnitMemberDtos.SetPasswordRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reinicios de contraseña. La ruta del encargado cuelga de su organización y la del
 * comprador de su vínculo con la unidad, igual que el alta de cada uno.
 */
@RestController
@RequestMapping("/api")
public class AccountResetController {

    private final AccountResetService resetService;

    public AccountResetController(AccountResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/organizations/{organizationId}/members/{memberId}/reset-password")
    public ResetPasswordResponse resetOrganizationMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @PathVariable UUID memberId) {
        return resetService.resetOrganizationMember(userId(jwt), organizationId, memberId);
    }

    /** Sin cuerpo o sin contraseña, se genera una; con contraseña, se usa esa y no se devuelve. */
    @PostMapping("/unit-members/{memberId}/reset-password")
    public ResetPasswordResponse resetUnitMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID memberId,
            @RequestBody(required = false) SetPasswordRequest request) {
        return resetService.resetUnitMember(userId(jwt), memberId, request == null ? null : request.password());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
