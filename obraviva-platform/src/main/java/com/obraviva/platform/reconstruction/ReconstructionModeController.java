package com.obraviva.platform.reconstruction;

import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import static com.obraviva.platform.auth.CurrentUser.userId;

/**
 * Informa si la reconstrucción 3D está en modo simulado y, al dueño, cuántos créditos quedan.
 *
 * <p>Con el servicio real, cada video consume un crédito y los créditos son escasos: la
 * interfaz lo usa para pedir una confirmación explícita antes de enviar. Lee la misma
 * propiedad que elige el adaptador, así el aviso no puede quedar desincronizado.
 */
@RestController
@RequestMapping("/api/reconstruction")
public class ReconstructionModeController {

    private final boolean simulated;
    private final ReconstructionCredits credits;
    private final OrganizationMembershipService membership;

    public ReconstructionModeController(
            @Value("${obraviva.reconstruction.provider:mock}") String provider,
            ReconstructionCredits credits,
            OrganizationMembershipService membership) {
        this.simulated = !"kiri".equalsIgnoreCase(provider.trim());
        this.credits = credits;
        this.membership = membership;
    }

    public record ReconstructionModeResponse(boolean simulated) {
    }

    /** {@code credits} nulo en modo simulado o si el proveedor no respondió: la pantalla no muestra número. */
    public record ReconstructionCreditsResponse(boolean simulated, Integer credits) {
    }

    @GetMapping("/mode")
    public ReconstructionModeResponse mode() {
        return new ReconstructionModeResponse(simulated);
    }

    /** Sólo para el dueño: los créditos son de la cuenta de la empresa, no del trabajo de campo. */
    @GetMapping("/credits")
    public ReconstructionCreditsResponse credits(@AuthenticationPrincipal Jwt jwt) {
        if (membership.ownedOrganizationIdsOf(userId(jwt)).isEmpty()) {
            throw OrganizationMembershipService.ownerOnly();
        }
        if (simulated) {
            return new ReconstructionCreditsResponse(true, null);
        }
        var available = credits.available();
        return new ReconstructionCreditsResponse(false, available.isPresent() ? available.getAsInt() : null);
    }
}
