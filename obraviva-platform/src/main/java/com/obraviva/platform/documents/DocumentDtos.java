package com.obraviva.platform.documents;

import com.obraviva.platform.documents.UnitDocument.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** {@code unitId} nulo: documento de la obra, para todos sus compradores. */
    public record RequestDocumentUploadRequest(
            @NotBlank @Size(max = 120) String title,
            @NotNull Category category,
            UUID unitId,
            @NotBlank @Size(max = 300) String filename,
            @NotBlank @Size(max = 100) String contentType) {
    }

    /** El navegador sube con PUT a {@code uploadUrl} con exactamente este Content-Type, y confirma. */
    public record DocumentUploadResponse(UUID documentId, String uploadUrl, String contentType) {
    }

    /**
     * Documento visto por quien tiene derecho a verlo. {@code downloadUrl} vence en minutos:
     * es un archivo privado, y una URL firmada no se puede revocar.
     */
    public record DocumentResponse(
            UUID id,
            UUID unitId,
            String title,
            Category category,
            String originalFilename,
            String contentType,
            boolean available,
            String downloadUrl,
            Instant createdAt) {
    }
}
