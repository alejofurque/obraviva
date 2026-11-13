package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DevelopmentDetailsService.CoverUploadResponse;
import com.obraviva.platform.developments.DevelopmentDetailsService.RequestCoverUploadRequest;
import com.obraviva.platform.developments.DevelopmentDetailsService.UpdateDevelopmentRequest;
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
@RequestMapping("/api/developments/{developmentId}")
public class DevelopmentDetailsController {

    private final DevelopmentDetailsService detailsService;

    public DevelopmentDetailsController(DevelopmentDetailsService detailsService) {
        this.detailsService = detailsService;
    }

    @PutMapping
    public DevelopmentResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId,
            @Valid @RequestBody UpdateDevelopmentRequest request) {
        return detailsService.update(userId(jwt), developmentId, request);
    }

    @PostMapping("/cover/upload-url")
    public CoverUploadResponse requestCoverUpload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId,
            @Valid @RequestBody RequestCoverUploadRequest request) {
        return detailsService.requestCoverUpload(userId(jwt), developmentId, request);
    }

    @PostMapping("/cover/confirm")
    public DevelopmentResponse confirmCover(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return detailsService.confirmCover(userId(jwt), developmentId);
    }
}
