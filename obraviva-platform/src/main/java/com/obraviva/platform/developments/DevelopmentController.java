package com.obraviva.platform.developments;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/developments")
public class DevelopmentController {

    private final DevelopmentService developmentService;

    public DevelopmentController(DevelopmentService developmentService) {
        this.developmentService = developmentService;
    }

    @GetMapping
    public List<DevelopmentResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return developmentService.listForUser(userId(jwt));
    }

    @GetMapping("/{developmentId}")
    public DevelopmentResponse detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return developmentService.detail(userId(jwt), developmentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DevelopmentResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDevelopmentRequest request) {
        return developmentService.create(userId(jwt), request);
    }

    @GetMapping("/{developmentId}/units")
    public List<UnitResponse> listUnits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return developmentService.listUnits(userId(jwt), developmentId);
    }

    @PostMapping("/{developmentId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse createUnit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateUnitRequest request) {
        return developmentService.createUnit(userId(jwt), developmentId, request);
    }

    @PostMapping("/{developmentId}/units/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<UnitResponse> createUnits(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody UnitDetailsDtos.CreateUnitsBatchRequest request) {
        return developmentService.createUnits(userId(jwt), developmentId, request);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

        @GetMapping("/{developmentId}/stages")
    public List<StageResponse> listStages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return developmentService.listStages(userId(jwt), developmentId);
    }

    @PostMapping("/{developmentId}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    public StageResponse createStage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateStageRequest request) {
        return developmentService.createStage(userId(jwt), developmentId, request);
    }

    @PatchMapping("/stages/{stageId}")
    public StageResponse updateStageStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID stageId,
            @Valid @RequestBody UpdateStageStatusRequest request) {
        return developmentService.updateStageStatus(userId(jwt), stageId, request);
    }
}