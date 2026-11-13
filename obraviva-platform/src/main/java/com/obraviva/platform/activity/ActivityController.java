package com.obraviva.platform.activity;

import com.obraviva.platform.activity.ActivityDtos.ActivityPage;
import com.obraviva.platform.activity.ActivityDtos.MarkSeenRequest;
import com.obraviva.platform.activity.ActivityDtos.PendingAudio;
import com.obraviva.platform.activity.ActivityDtos.UnseenCount;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

/** Novedades de un desarrollo. Ver {@link ActivityService} para los permisos. */
@RestController
@RequestMapping("/api/developments/{developmentId}/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    /**
     * {@code before} es el {@code nextCursor} de la página anterior. {@code kind}: ENTRY,
     * AUDIO (avances que salieron de un audio), PHOTOS o CAPTURE.
     */
    @GetMapping
    public ActivityPage feed(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) ActivityService.Kind kind,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Integer limit) {
        return activityService.feed(userId(jwt), developmentId, unitId, authorId, kind, before, limit);
    }

    @GetMapping("/unseen-count")
    public UnseenCount unseenCount(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return activityService.unseenCount(userId(jwt), developmentId);
    }

    @PutMapping("/seen")
    public ResponseEntity<Void> markSeen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody MarkSeenRequest request) {
        activityService.markSeen(userId(jwt), developmentId, request.until());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pending-audios")
    public List<PendingAudio> pendingAudios(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return activityService.pendingAudios(userId(jwt), developmentId);
    }
}
