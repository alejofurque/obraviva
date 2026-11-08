package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DevelopmentTeamDtos.AssignSupervisorRequest;
import com.obraviva.platform.developments.DevelopmentTeamDtos.DevelopmentTeamResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/developments/{developmentId}/team")
public class DevelopmentTeamController {

    private final DevelopmentTeamService teamService;

    public DevelopmentTeamController(DevelopmentTeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public DevelopmentTeamResponse team(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return teamService.team(userId(jwt), developmentId);
    }

    @PostMapping
    public DevelopmentTeamResponse assign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody AssignSupervisorRequest request) {
        return teamService.assign(userId(jwt), developmentId, request);
    }

    /** En qué casas trabaja: vacío, todo el desarrollo. */
    @org.springframework.web.bind.annotation.PutMapping("/{supervisorId}")
    public DevelopmentTeamResponse changeScope(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @PathVariable UUID supervisorId,
            @Valid @RequestBody DevelopmentTeamDtos.SupervisorScopeRequest request) {
        return teamService.changeScope(userId(jwt), developmentId, supervisorId, request);
    }

    @DeleteMapping("/{supervisorId}")
    public DevelopmentTeamResponse unassign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @PathVariable UUID supervisorId) {
        return teamService.unassign(userId(jwt), developmentId, supervisorId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
