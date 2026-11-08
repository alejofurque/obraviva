package com.obraviva.platform.ai;

import com.obraviva.platform.ai.AudioDtos.ApprovalResultResponse;
import com.obraviva.platform.ai.AudioDtos.ApproveExtractionRequest;
import com.obraviva.platform.ai.AudioDtos.AudioExtractionJobResponse;
import com.obraviva.platform.ai.AudioDtos.CreateAudioExtractionRequest;
import com.obraviva.platform.ai.AudioDtos.ExtractionDetailResponse;
import com.obraviva.platform.ai.AudioDtos.ExtractionSummaryResponse;
import com.obraviva.platform.ai.AudioDtos.RejectExtractionRequest;
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
@RequestMapping("/api")
public class AudioExtractionController {

    private final AudioExtractionService extractionService;

    public AudioExtractionController(AudioExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    // --- Alta ---

    @PostMapping("/developments/{developmentId}/audio-extractions")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioExtractionJobResponse createFromAudio(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateAudioExtractionRequest request) {
        return extractionService.createFromAudio(userId(jwt), developmentId, request);
    }

    @PostMapping("/audio-extractions/{extractionId}/confirm-audio")
    public AudioExtractionJobResponse confirmAudioUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID extractionId) {
        return extractionService.confirmAudioUpload(userId(jwt), extractionId);
    }

    // --- Revisión ---

    @GetMapping("/audio-extractions/{extractionId}")
    public ExtractionDetailResponse detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID extractionId) {
        return extractionService.detail(userId(jwt), extractionId);
    }

    @GetMapping("/developments/{developmentId}/audio-extractions")
    public List<ExtractionSummaryResponse> listByDevelopment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return extractionService.listByDevelopment(userId(jwt), developmentId);
    }

    @GetMapping("/audio-extractions/pending-review")
    public List<ExtractionSummaryResponse> listPendingReview(@AuthenticationPrincipal Jwt jwt) {
        return extractionService.listPendingReview(userId(jwt));
    }

    @PostMapping("/audio-extractions/{extractionId}/approve")
    public ApprovalResultResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID extractionId,
            @Valid @RequestBody ApproveExtractionRequest request) {
        return extractionService.approve(userId(jwt), extractionId, request);
    }

    @PostMapping("/audio-extractions/{extractionId}/reject")
    public ExtractionDetailResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID extractionId,
            @Valid @RequestBody RejectExtractionRequest request) {
        return extractionService.reject(userId(jwt), extractionId, request);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}