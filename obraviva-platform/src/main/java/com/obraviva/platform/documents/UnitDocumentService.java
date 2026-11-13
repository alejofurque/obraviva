package com.obraviva.platform.documents;

import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.UnitAccessService;
import com.obraviva.platform.developments.UnitAccessService.UnitAccess;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.documents.DocumentDtos.DocumentResponse;
import com.obraviva.platform.documents.DocumentDtos.DocumentUploadResponse;
import com.obraviva.platform.documents.DocumentDtos.RequestDocumentUploadRequest;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Documentos autorizados.
 *
 * <p>Tres reglas: los administra el dueño; los ve el comprador de la unidad (los de su
 * unidad y los de la obra); y no salen nunca por el enlace público ni hacia el encargado.
 * El dueño también puede verlos como los ve el comprador, desde la vista de la unidad.
 */
@Service
public class UnitDocumentService {

    private static final Logger log = LoggerFactory.getLogger(UnitDocumentService.class);

    private static final Set<String> ACCEPTED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    /** Suficiente para abrir el documento; corta a propósito porque la URL no se revoca. */
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(15);

    private final UnitDocumentRepository documentRepository;
    private final DevelopmentAccessService developmentAccess;
    private final UnitAccessService unitAccess;
    private final UnitCatalogService unitCatalog;
    private final OrganizationMembershipService membershipService;
    private final StorageService storageService;

    public UnitDocumentService(
            UnitDocumentRepository documentRepository,
            DevelopmentAccessService developmentAccess,
            UnitAccessService unitAccess,
            UnitCatalogService unitCatalog,
            OrganizationMembershipService membershipService,
            StorageService storageService) {
        this.documentRepository = documentRepository;
        this.developmentAccess = developmentAccess;
        this.unitAccess = unitAccess;
        this.unitCatalog = unitCatalog;
        this.membershipService = membershipService;
        this.storageService = storageService;
    }

    /** Todos los documentos de la obra, para el dueño. */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listForOwner(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        return documentRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtDesc(developmentId, List.of(organizationId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DocumentUploadResponse requestUpload(UUID userId, UUID developmentId, RequestDocumentUploadRequest request) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        if (!ACCEPTED_TYPES.contains(request.contentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El documento tiene que ser PDF, JPG o PNG");
        }
        if (request.unitId() != null) {
            boolean belongs = unitCatalog.findInOrganization(request.unitId(), organizationId)
                    .filter(unit -> unit.developmentId().equals(developmentId))
                    .isPresent();
            if (!belongs) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
            }
        }
        UnitDocument document = documentRepository.save(new UnitDocument(
                organizationId, developmentId, request.unitId(), request.title().trim(), request.category(),
                request.filename(), request.contentType(), userId));
        return new DocumentUploadResponse(
                document.getId(),
                storageService.presignUpload(document.getStorageKey(), request.contentType()),
                request.contentType());
    }

    @Transactional
    public DocumentResponse confirm(UUID userId, UUID documentId) {
        UnitDocument document = requireOwned(userId, documentId);
        if (!storageService.exists(document.getStorageKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El documento no se encuentra en el almacenamiento");
        }
        document.markAvailable();
        log.info("Documento {} publicado por {} (unidad {})", documentId, userId, document.getUnitId());
        return toResponse(document);
    }

    /** Documento desde un archivo que ya está en el servidor, para los datos de demostración. */
    @Transactional
    public DocumentResponse importDocument(
            UUID userId, UUID developmentId, RequestDocumentUploadRequest request, Path file) {
        DocumentUploadResponse upload = requestUpload(userId, developmentId, request);
        UnitDocument document = documentRepository.findById(upload.documentId()).orElseThrow();
        storageService.upload(file, document.getStorageKey(), request.contentType());
        document.markAvailable();
        return toResponse(document);
    }

    @Transactional
    public void delete(UUID userId, UUID documentId) {
        UnitDocument document = requireOwned(userId, documentId);
        documentRepository.delete(document);
        try {
            storageService.delete(document.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el archivo del documento {}: queda huérfano", documentId);
        }
    }

    /**
     * Documentos de una unidad, como los ve su comprador: los de la unidad y los de la obra,
     * ya disponibles. El dueño llega igual, para ver lo mismo que el comprador; el encargado
     * recibe 403.
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listForUnit(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);
        if (!access.isBuyer() && !membershipService.isOwnerOf(userId, access.unit().organizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return documentRepository
                .findByDevelopmentIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                        access.unit().developmentId(), access.unit().organizationId(), UnitDocument.Status.AVAILABLE)
                .stream()
                .filter(document -> document.getUnitId() == null || document.getUnitId().equals(unitId))
                .map(this::toResponse)
                .toList();
    }

    private UnitDocument requireOwned(UUID userId, UUID documentId) {
        UnitDocument document = documentRepository
                .findByIdAndOrganizationIdIn(documentId, membershipService.organizationIdsOf(userId))
                .filter(found -> developmentAccess.canAccess(userId, found.getDevelopmentId(), found.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado"));
        if (!membershipService.isOwnerOf(userId, document.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return document;
    }

    private DocumentResponse toResponse(UnitDocument document) {
        boolean available = document.getStatus() == UnitDocument.Status.AVAILABLE;
        return new DocumentResponse(
                document.getId(),
                document.getUnitId(),
                document.getTitle(),
                document.getCategory(),
                document.getOriginalFilename(),
                document.getContentType(),
                available,
                available ? storageService.presignDownload(document.getStorageKey(), DOWNLOAD_TTL) : null,
                document.getCreatedAt());
    }
}
