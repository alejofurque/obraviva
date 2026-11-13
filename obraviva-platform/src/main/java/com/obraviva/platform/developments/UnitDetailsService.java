package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitDetailsDtos.CoverUploadResponse;
import com.obraviva.platform.developments.UnitDetailsDtos.RequestCoverUploadRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitDetailsRequest;
import com.obraviva.platform.media.variants.ImageVariants;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ficha y portada de una unidad. Es información comercial de la unidad: la edita el
 * dueño del desarrollo, no el encargado ni el comprador.
 */
@Service
public class UnitDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UnitDetailsService.class);

    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,5}");

    private final UnitRepository unitRepository;
    private final OrganizationMembershipService membershipService;
    private final StorageService storageService;
    private final ImageVariants imageVariants;

    public UnitDetailsService(
            UnitRepository unitRepository,
            OrganizationMembershipService membershipService,
            StorageService storageService,
            ImageVariants imageVariants) {
        this.unitRepository = unitRepository;
        this.membershipService = membershipService;
        this.storageService = storageService;
        this.imageVariants = imageVariants;
    }

    @Transactional
    public UnitResponse updateDetails(UUID userId, UUID unitId, UpdateUnitDetailsRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);
        unit.replaceDetails(
                request.areaM2(),
                request.bedrooms(),
                request.bathrooms(),
                cleanFeatures(request.features()),
                request.estimatedDelivery());
        return UnitResponse.from(unit);
    }

    @Transactional
    public CoverUploadResponse requestCoverUpload(
            UUID userId, UUID unitId, RequestCoverUploadRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);

        if (!request.contentType().startsWith("image/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "La portada tiene que ser una imagen");
        }

        String key = unit.prepareCoverUpload(extensionOf(request.filename()));
        return new CoverUploadResponse(
                storageService.presignUpload(key, request.contentType()), request.contentType());
    }

    /** Ficha de venta ampliada: lo que hace falta para mostrar y vender la casa. */
    @Transactional
    public UnitResponse updateSalesSheet(UUID userId, UUID unitId, UnitDetailsDtos.UpdateSalesSheetRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);
        try {
            unit.replaceSalesSheet(new Unit.SalesSheet(
                    request.modelName(), request.lotAreaM2(), request.coveredAreaM2(), request.semiCoveredAreaM2(),
                    request.rooms(), request.toilettes(), request.floors(), request.parkingSpaces(),
                    request.orientation(), request.longDescription(), request.finishes(),
                    request.listPrice(), request.priceCurrency()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return UnitResponse.from(unit);
    }

    /** Estado comercial, con el día de hoy. Lo decide el dueño: el encargado no vende. */
    @Transactional
    public UnitResponse changeSalesStatus(UUID userId, UUID unitId, UnitSalesStatus status) {
        return changeSalesStatus(userId, unitId, status, null);
    }

    /**
     * Estado comercial y el día de la reserva o la venta; sin día, hoy. No puede ser futuro.
     * Disponible no lleva día.
     */
    @Transactional
    public UnitResponse changeSalesStatus(UUID userId, UUID unitId, UnitSalesStatus status, LocalDate on) {
        Unit unit = requireOwnedUnit(userId, unitId);
        LocalDate today = LocalDate.now();
        if (on != null && on.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha de la reserva o la venta no puede ser futura");
        }
        unit.changeSalesStatus(status, on == null ? today : on);
        log.info("Unidad {} pasa a {} por {}", unitId, status, userId);
        return UnitResponse.from(unit);
    }

    /** Portada desde un archivo que ya está en el servidor, para los datos de demostración. */
    @Transactional
    public UnitResponse importCover(
            UUID userId, UUID unitId, String filename, String contentType, Path file) {
        requestCoverUpload(userId, unitId, new RequestCoverUploadRequest(filename, contentType));
        Unit unit = requireOwnedUnit(userId, unitId);
        storageService.upload(file, unit.getCoverPendingStorageKey(), contentType);
        replaced(unit, unit.confirmCover(), unit.getCoverStorageKey());
        return UnitResponse.from(unit);
    }

    /**
     * Confirma la portada verificando que el archivo llegó. La anterior no se borra del
     * almacenamiento: borrar dentro de la transacción y fallar después dejaría la
     * unidad sin portada, y un archivo huérfano por reemplazo no cuesta nada relevante.
     */
    @Transactional
    public UnitResponse confirmCover(UUID userId, UUID unitId) {
        Unit unit = requireOwnedUnit(userId, unitId);

        String pending = unit.getCoverPendingStorageKey();
        if (pending == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No hay una portada pendiente de confirmar");
        }
        if (!storageService.exists(pending)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La imagen no se encuentra en el almacenamiento");
        }

        String previous = unit.confirmCover();
        replaced(unit, previous, unit.getCoverStorageKey());
        log.info("Portada de la unidad {} actualizada por {} (reemplaza: {})",
                unitId, userId, previous != null);
        return UnitResponse.from(unit);
    }

    /**
     * Pide dónde subir el render del frente. Mismo pedido y misma respuesta que la portada:
     * es otra imagen de la casa, no un medio de obra.
     */
    @Transactional
    public CoverUploadResponse requestFrontRenderUpload(
            UUID userId, UUID unitId, RequestCoverUploadRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);

        if (!request.contentType().startsWith("image/")) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El render tiene que ser una imagen");
        }

        String key = unit.prepareFrontRenderUpload(extensionOf(request.filename()));
        return new CoverUploadResponse(
                storageService.presignUpload(key, request.contentType()), request.contentType());
    }

    /** Confirma el render del frente verificando que el archivo llegó, como la portada. */
    @Transactional
    public UnitResponse confirmFrontRender(UUID userId, UUID unitId) {
        Unit unit = requireOwnedUnit(userId, unitId);

        String pending = unit.getFrontRenderPendingStorageKey();
        if (pending == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No hay un render pendiente de confirmar");
        }
        if (!storageService.exists(pending)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La imagen no se encuentra en el almacenamiento");
        }

        String previous = unit.confirmFrontRender();
        replaced(unit, previous, unit.getFrontRenderStorageKey());
        log.info("Render del frente de la unidad {} actualizado por {} (reemplaza: {})",
                unitId, userId, previous != null);
        return UnitResponse.from(unit);
    }

    /**
     * Las versiones livianas siguen a la imagen: la anterior deja de tenerlas (el original queda,
     * como siempre) y la nueva entra a la cola del worker.
     */
    private void replaced(Unit unit, String previous, String current) {
        imageVariants.discard(unit.getOrganizationId(), previous);
        imageVariants.enqueue(unit.getOrganizationId(), current);
    }

    /**
     * La ficha se administra desde la organización y la edita el dueño. Un comprador no
     * llega a la unidad por la organización, así que para él no existe: 404.
     */
    private Unit requireOwnedUnit(UUID userId, UUID unitId) {
        Unit unit = unitRepository
                .findByIdAndOrganizationIdIn(unitId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));
        if (!membershipService.isOwnerOf(userId, unit.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return unit;
    }

    /** Sin espacios alrededor, sin vacíos y sin repetidos, respetando el orden cargado. */
    private static List<String> cleanFeatures(List<String> features) {
        if (features == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String feature : features) {
            if (feature == null || feature.isBlank()) {
                continue;
            }
            String trimmed = feature.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        // La extensión termina en una clave de almacenamiento: sólo se acepta algo inocuo.
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "img";
    }
}
