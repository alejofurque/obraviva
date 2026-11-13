package com.obraviva.platform.developments;

import com.obraviva.platform.media.variants.ImageVariants;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Datos generales del desarrollo: lo que un comprador quiere saber del barrio antes de
 * mirar su casa. Los edita el dueño.
 */
@Service
public class DevelopmentDetailsService {

    private static final Map<String, String> COVER_TYPES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final DevelopmentAccessService developmentAccess;
    private final DevelopmentService developmentService;
    private final OrganizationMembershipService membershipService;
    private final StorageService storageService;

    private final ImageVariants imageVariants;

    public DevelopmentDetailsService(
            DevelopmentAccessService developmentAccess,
            DevelopmentService developmentService,
            OrganizationMembershipService membershipService,
            StorageService storageService,
            ImageVariants imageVariants) {
        this.developmentAccess = developmentAccess;
        this.developmentService = developmentService;
        this.membershipService = membershipService;
        this.storageService = storageService;
        this.imageVariants = imageVariants;
    }

    /** Todos opcionales salvo el nombre; lo ausente queda vacío. */
    public record UpdateDevelopmentRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 300) String address,
            @Size(max = 4000) String description,
            @Size(max = 120) String locality,
            @Size(max = 80) String province,
            DevelopmentType developmentType,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal landAreaM2,
            LocalDate startedOn,
            YearMonth estimatedDelivery,
            @Size(max = 20) List<@Size(max = 60) String> amenities,
            @Size(max = 20) List<@Size(max = 60) String> services,
            @Size(max = 160) String architect,
            @Size(max = 160) String builder,
            @Size(max = 80) String permitNumber) {
    }

    public record RequestCoverUploadRequest(@NotBlank @Size(max = 300) String filename, @NotBlank @Size(max = 100) String contentType) {
    }

    public record CoverUploadResponse(String uploadUrl, String contentType) {
    }

    @Transactional
    public DevelopmentResponse update(UUID userId, UUID developmentId, UpdateDevelopmentRequest request) {
        Development development = requireOwned(userId, developmentId);
        development.replaceDetails(new Development.DevelopmentDetails(
                request.name(), request.address(), request.description(), request.locality(), request.province(),
                request.developmentType(), request.landAreaM2(), request.startedOn(),
                request.estimatedDelivery() == null ? null : request.estimatedDelivery().atDay(1),
                clean(request.amenities()), clean(request.services()),
                request.architect(), request.builder(), request.permitNumber()));
        return developmentService.toResponse(development, OrganizationRole.OWNER, membershipService.organizationIdsOf(userId));
    }

    @Transactional
    public CoverUploadResponse requestCoverUpload(UUID userId, UUID developmentId, RequestCoverUploadRequest request) {
        String extension = COVER_TYPES.get(request.contentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "La portada tiene que ser una imagen JPG, PNG o WebP");
        }
        String key = requireOwned(userId, developmentId).prepareCoverUpload(extension);
        return new CoverUploadResponse(storageService.presignUpload(key, request.contentType()), request.contentType());
    }

    @Transactional
    public DevelopmentResponse confirmCover(UUID userId, UUID developmentId) {
        Development development = requireOwned(userId, developmentId);
        if (development.getCoverPendingStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay una portada pendiente de confirmar");
        }
        if (!storageService.exists(development.getCoverPendingStorageKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La imagen no se encuentra en el almacenamiento");
        }
        coverReplaced(development, development.confirmCover());
        return developmentService.toResponse(development, OrganizationRole.OWNER, membershipService.organizationIdsOf(userId));
    }

    /** Portada desde un archivo del servidor, para los datos de demostración. */
    @Transactional
    public DevelopmentResponse importCover(UUID userId, UUID developmentId, Path file, String contentType) {
        String extension = COVER_TYPES.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Tipo de portada no aceptado: " + contentType);
        }
        Development development = requireOwned(userId, developmentId);
        storageService.upload(file, development.prepareCoverUpload(extension), contentType);
        coverReplaced(development, development.confirmCover());
        return developmentService.toResponse(development, OrganizationRole.OWNER, membershipService.organizationIdsOf(userId));
    }

    /** La portada anterior deja de tener versiones livianas (el original queda) y la nueva entra a la cola. */
    private void coverReplaced(Development development, String previous) {
        imageVariants.discard(development.getOrganizationId(), previous);
        imageVariants.enqueue(development.getOrganizationId(), development.getCoverStorageKey());
    }

    private Development requireOwned(UUID userId, UUID developmentId) {
        Development development = developmentAccess.requireVisible(userId, developmentId);
        if (!membershipService.isOwnerOf(userId, development.getOrganizationId())) {
            throw DevelopmentAccessService.ownerOnly();
        }
        return development;
    }

    /** Sin espacios alrededor, sin vacíos y sin repetidos, respetando el orden cargado. */
    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && seen.add(value.trim().toLowerCase(Locale.ROOT))) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }
}
