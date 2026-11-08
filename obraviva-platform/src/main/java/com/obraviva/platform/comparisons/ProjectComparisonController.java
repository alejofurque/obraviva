package com.obraviva.platform.comparisons;

import com.obraviva.platform.comparisons.ComparisonDtos.ComparisonResponse;
import com.obraviva.platform.comparisons.ComparisonDtos.CreateComparisonRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.RenderUploadResponse;
import com.obraviva.platform.comparisons.ComparisonDtos.RequestRenderUploadRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.UpdateComparisonRequest;
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

@RestController
@RequestMapping("/api")
public class ProjectComparisonController {

    private final ProjectComparisonService comparisonService;

    public ProjectComparisonController(ProjectComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @GetMapping("/developments/{developmentId}/comparisons")
    public List<ComparisonResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return comparisonService.list(userId(jwt), developmentId);
    }

    @PostMapping("/developments/{developmentId}/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    public ComparisonResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateComparisonRequest request) {
        return comparisonService.create(userId(jwt), developmentId, request);
    }

    @PutMapping("/comparisons/{comparisonId}")
    public ComparisonResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID comparisonId,
            @Valid @RequestBody UpdateComparisonRequest request) {
        return comparisonService.update(userId(jwt), comparisonId, request);
    }

    @PostMapping("/comparisons/{comparisonId}/render/upload-url")
    public RenderUploadResponse requestRenderUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID comparisonId,
            @Valid @RequestBody RequestRenderUploadRequest request) {
        return comparisonService.requestRenderUpload(userId(jwt), comparisonId, request);
    }

    @PostMapping("/comparisons/{comparisonId}/render/confirm")
    public ComparisonResponse confirmRender(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID comparisonId) {
        return comparisonService.confirmRender(userId(jwt), comparisonId);
    }

    @DeleteMapping("/comparisons/{comparisonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID comparisonId) {
        comparisonService.delete(userId(jwt), comparisonId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
