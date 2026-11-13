package com.obraviva.platform.progress;

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

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/developments/{developmentId}/progress")
    public List<ProgressEntryResponse> timeline(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return progressService.timeline(userId(jwt), developmentId);
    }

    // El alta (POST /developments/{id}/progress) está en publication.ProgressPublicationController:
    // publica el avance con sus fotos y el cambio de etapa en una sola transacción.

    /** Corrección: crea una versión nueva, no modifica la existente. */
    @PostMapping("/progress/{entryId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressEntryResponse correct(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateProgressEntryRequest request) {
        return progressService.createCorrection(userId(jwt), entryId, request);
    }
}