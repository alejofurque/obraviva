package com.obraviva.platform.media;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/developments/{developmentId}/media/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadUrlResponse requestUploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody RequestUploadRequest request) {
        return mediaService.requestUploadUrl(userId(jwt), developmentId, request);
    }

    @PostMapping("/media/{mediaId}/confirm")
    public MediaAssetResponse confirmUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID mediaId) {
        return mediaService.confirmUpload(userId(jwt), mediaId);
    }

    /** El lugar de la casa de una foto o video ya subido. */
    public record UpdateMediaSectorRequest(@Size(max = 80) String sector) {
    }

    @PatchMapping("/media/{mediaId}/sector")
    public MediaAssetResponse updateSector(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId,
            @Valid @RequestBody UpdateMediaSectorRequest request) {
        return mediaService.updateSector(userId(jwt), mediaId, request.sector());
    }

    /** "Mover a fotos de obra": el lugar y el día en que se sacó. */
    public record MoveToWorkRequest(
            @Size(max = 80) String sector, LocalDate takenOn) {
    }

    @PostMapping("/media/{mediaId}/to-work")
    public MediaAssetResponse moveToWork(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId,
            @Valid @RequestBody MoveToWorkRequest request) {
        return mediaService.moveToWork(userId(jwt), mediaId, request.sector(), request.takenOn());
    }

    @DeleteMapping("/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        mediaService.delete(userId(jwt), mediaId);
    }

    @GetMapping("/developments/{developmentId}/media")
    public List<MediaAssetResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return mediaService.listAvailable(userId(jwt), developmentId);
    }
}