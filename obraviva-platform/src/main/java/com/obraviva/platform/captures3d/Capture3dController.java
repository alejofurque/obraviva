package com.obraviva.platform.captures3d;

import com.obraviva.platform.captures3d.CaptureDtos.CaptureJobResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureProcessingResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureUploadResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateTransformRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class Capture3dController {

    private final Capture3dService captureService;
    private final VideoCaptureService videoCaptureService;

    public Capture3dController(Capture3dService captureService, VideoCaptureService videoCaptureService) {
        this.captureService = captureService;
        this.videoCaptureService = videoCaptureService;
    }

    @PostMapping("/developments/{developmentId}/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public CaptureUploadResponse createCapture(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateCaptureRequest request) {
        return captureService.createCapture(userId(jwt), developmentId, request);
    }

    @PostMapping("/captures/{captureId}/confirm")
    public CaptureResponse confirmUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID captureId) {
        return captureService.confirmUpload(userId(jwt), captureId);
    }

    @GetMapping("/developments/{developmentId}/captures")
    public List<CaptureResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return captureService.listAvailable(userId(jwt), developmentId);
    }

    @GetMapping("/developments/{developmentId}/captures/processing")
    public List<CaptureProcessingResponse> listProcessing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return captureService.listProcessing(userId(jwt), developmentId);
    }

    @DeleteMapping("/captures/{captureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID captureId) {
        captureService.discard(userId(jwt), captureId);
    }

    @PatchMapping("/captures/{captureId}")
    public CaptureResponse updateCapture(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID captureId,
            @Valid @RequestBody UpdateCaptureRequest request) {
        return captureService.updateCapture(userId(jwt), captureId, request);
    }

    @PatchMapping("/captures/{captureId}/transform")
    public CaptureResponse updateTransform(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID captureId,
            @Valid @RequestBody UpdateTransformRequest request) {
        return captureService.updateTransform(userId(jwt), captureId, request);
    }

        @PostMapping("/developments/{developmentId}/captures/from-video")
    @ResponseStatus(HttpStatus.CREATED)
    public CaptureJobResponse createFromVideo(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateCaptureFromVideoRequest request) {
        return videoCaptureService.createFromVideo(userId(jwt), developmentId, request);
    }

    @PostMapping("/captures/{captureId}/confirm-video")
    public CaptureJobResponse confirmVideoUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID captureId) {
        return videoCaptureService.confirmVideoUpload(userId(jwt), captureId);
    }
}