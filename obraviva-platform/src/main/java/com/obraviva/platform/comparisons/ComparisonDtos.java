package com.obraviva.platform.comparisons;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ComparisonDtos {

    private ComparisonDtos() {
    }

    /** {@code unitId} nulo: de toda la obra. */
    public record CreateComparisonRequest(
            @NotBlank @Size(max = 120) String title,
            UUID unitId) {
    }

    /** Reemplaza título, alcance y foto. Una foto nula deja la comparación sin publicar. */
    public record UpdateComparisonRequest(
            @NotBlank @Size(max = 120) String title,
            UUID unitId,
            UUID photoMediaId) {
    }

    public record RequestRenderUploadRequest(
            @NotBlank @Size(max = 300) String filename,
            @NotBlank @Size(max = 100) String contentType) {
    }

    public record RenderUploadResponse(String uploadUrl, String contentType) {
    }

    /** Para el dueño. Las URLs vienen firmadas; {@code complete} dice si el comprador la ve. */
    public record ComparisonResponse(
            UUID id,
            UUID developmentId,
            UUID unitId,
            String title,
            String renderUrl,
            String renderThumbUrl,
            String renderScreenUrl,
            UUID photoMediaId,
            String photoUrl,
            boolean complete,
            Instant createdAt) {
    }
}
