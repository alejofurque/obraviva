package com.obraviva.platform.developments;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class StagePlanController {

    private final StagePlanService stagePlanService;

    public StagePlanController(StagePlanService stagePlanService) {
        this.stagePlanService = stagePlanService;
    }

    @PatchMapping("/stages/{stageId}/plan")
    public StageResponse updatePlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID stageId,
            @Valid @RequestBody UpdateStagePlanRequest request) {
        return stagePlanService.updatePlan(userId(jwt), stageId, request);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}