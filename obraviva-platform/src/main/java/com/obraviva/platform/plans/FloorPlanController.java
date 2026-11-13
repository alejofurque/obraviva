package com.obraviva.platform.plans;

import com.obraviva.platform.plans.FloorPlanDtos.CreateFloorPlanRequest;
import com.obraviva.platform.plans.FloorPlanDtos.FloorPlanResponse;
import com.obraviva.platform.plans.FloorPlanDtos.PlanImageUploadResponse;
import com.obraviva.platform.plans.FloorPlanDtos.RequestPlanImageUploadRequest;
import com.obraviva.platform.plans.FloorPlanDtos.ReplaceRoomsRequest;
import com.obraviva.platform.plans.FloorPlanDtos.UpdateFloorPlanRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class FloorPlanController {

    private final FloorPlanService planService;
    private final FloorPlanImageService imageService;

    public FloorPlanController(FloorPlanService planService, FloorPlanImageService imageService) {
        this.planService = planService;
        this.imageService = imageService;
    }

    @GetMapping("/units/{unitId}/plans")
    public List<FloorPlanResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return planService.list(userId(jwt), unitId);
    }

    @PostMapping("/units/{unitId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public FloorPlanResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody CreateFloorPlanRequest request) {
        return planService.create(userId(jwt), unitId, request);
    }

    @PutMapping("/plans/{planId}")
    public FloorPlanResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID planId,
            @Valid @RequestBody UpdateFloorPlanRequest request) {
        return planService.update(userId(jwt), planId, request);
    }

    @PutMapping("/plans/{planId}/rooms")
    public FloorPlanResponse replaceRooms(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID planId,
            @Valid @RequestBody ReplaceRoomsRequest request) {
        return planService.replaceRooms(userId(jwt), planId, request);
    }

    @PostMapping("/plans/{planId}/image/upload-url")
    public PlanImageUploadResponse requestImageUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID planId,
            @Valid @RequestBody RequestPlanImageUploadRequest request) {
        return imageService.requestUpload(userId(jwt), planId, request);
    }

    @PostMapping("/plans/{planId}/image/confirm")
    public FloorPlanResponse confirmImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        return imageService.confirm(userId(jwt), planId);
    }

    @DeleteMapping("/plans/{planId}/image")
    public FloorPlanResponse removeImage(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        return imageService.removeImage(userId(jwt), planId);
    }

    @DeleteMapping("/plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        planService.delete(userId(jwt), planId);
    }
}
