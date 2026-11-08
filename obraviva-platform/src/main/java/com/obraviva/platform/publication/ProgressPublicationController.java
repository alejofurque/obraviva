package com.obraviva.platform.publication;

import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.progress.ProgressEntryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Alta de avances, del dueño y del encargado: con fotos y cambio de etapa opcionales. */
@RestController
@RequestMapping("/api")
public class ProgressPublicationController {

    private final ProgressPublicationService publicationService;

    public ProgressPublicationController(ProgressPublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @PostMapping("/developments/{developmentId}/progress")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressEntryResponse publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateProgressEntryRequest request) {
        return publicationService.publish(UUID.fromString(jwt.getSubject()), developmentId, request);
    }
}
