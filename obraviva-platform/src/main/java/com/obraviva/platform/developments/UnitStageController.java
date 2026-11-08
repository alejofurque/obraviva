package com.obraviva.platform.developments;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/units/{unitId}/stages")
public class UnitStageController {

    private final UnitStageService stageService;

    public UnitStageController(UnitStageService stageService) {
        this.stageService = stageService;
    }

    @GetMapping
    public List<StageResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return stageService.list(userId(jwt), unitId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StageResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody CreateStageRequest request) {
        return stageService.create(userId(jwt), unitId, request);
    }

    @PostMapping("/standard")
    @ResponseStatus(HttpStatus.CREATED)
    public List<StageResponse> applyStandard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return stageService.applyStandardStages(userId(jwt), unitId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
