package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitDetailsDtos.CoverUploadResponse;
import com.obraviva.platform.developments.UnitDetailsDtos.RequestCoverUploadRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitDetailsRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api/units/{unitId}")
public class UnitDetailsController {

    private final UnitDetailsService detailsService;
    private final DevelopmentService developmentService;

    public UnitDetailsController(UnitDetailsService detailsService, DevelopmentService developmentService) {
        this.detailsService = detailsService;
        this.developmentService = developmentService;
    }

    /** Nombre y descripción corta de la casa. */
    @PutMapping
    public UnitResponse updateUnit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody UnitDetailsDtos.UpdateUnitRequest request) {
        return developmentService.updateUnit(userId(jwt), unitId, request);
    }

    @PutMapping("/details")
    public UnitResponse updateDetails(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody UpdateUnitDetailsRequest request) {
        return detailsService.updateDetails(userId(jwt), unitId, request);
    }

    @PutMapping("/sales-status")
    public UnitResponse changeSalesStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody UnitDetailsDtos.UpdateSalesStatusRequest request) {
        return detailsService.changeSalesStatus(userId(jwt), unitId, request.status(), request.on());
    }

    @PutMapping("/sales-sheet")
    public UnitResponse updateSalesSheet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody UnitDetailsDtos.UpdateSalesSheetRequest request) {
        return detailsService.updateSalesSheet(userId(jwt), unitId, request);
    }

    @PostMapping("/cover/upload-url")
    public CoverUploadResponse requestCoverUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody RequestCoverUploadRequest request) {
        return detailsService.requestCoverUpload(userId(jwt), unitId, request);
    }

    @PostMapping("/cover/confirm")
    public UnitResponse confirmCover(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId) {
        return detailsService.confirmCover(userId(jwt), unitId);
    }

    @PostMapping("/front-render/upload-url")
    public CoverUploadResponse requestFrontRenderUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId,
            @Valid @RequestBody RequestCoverUploadRequest request) {
        return detailsService.requestFrontRenderUpload(userId(jwt), unitId, request);
    }

    @PostMapping("/front-render/confirm")
    public UnitResponse confirmFrontRender(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId) {
        return detailsService.confirmFrontRender(userId(jwt), unitId);
    }
}
