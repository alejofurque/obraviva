package com.obraviva.platform.documents;

import com.obraviva.platform.documents.DocumentDtos.DocumentResponse;
import com.obraviva.platform.documents.DocumentDtos.DocumentUploadResponse;
import com.obraviva.platform.documents.DocumentDtos.RequestDocumentUploadRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class UnitDocumentController {

    private final UnitDocumentService documentService;

    public UnitDocumentController(UnitDocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/developments/{developmentId}/documents")
    public List<DocumentResponse> listForOwner(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID developmentId) {
        return documentService.listForOwner(userId(jwt), developmentId);
    }

    @PostMapping("/developments/{developmentId}/documents/upload-url")
    public DocumentUploadResponse requestUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody RequestDocumentUploadRequest request) {
        return documentService.requestUpload(userId(jwt), developmentId, request);
    }

    @PostMapping("/documents/{documentId}/confirm")
    public DocumentResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        return documentService.confirm(userId(jwt), documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        documentService.delete(userId(jwt), documentId);
    }

    /** Lo que ve el comprador en "Mi propiedad". No existe equivalente en la vista pública. */
    @GetMapping("/my-units/{unitId}/documents")
    public List<DocumentResponse> listForUnit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
        return documentService.listForUnit(userId(jwt), unitId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
