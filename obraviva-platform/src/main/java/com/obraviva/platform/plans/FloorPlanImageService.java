package com.obraviva.platform.plans;

import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.plans.FloorPlanDtos.FloorPlanResponse;
import com.obraviva.platform.plans.FloorPlanDtos.PlanImageUploadResponse;
import com.obraviva.platform.plans.FloorPlanDtos.RequestPlanImageUploadRequest;
import com.obraviva.platform.plans.PlanImageProcessor.InvalidPlanImageException;
import com.obraviva.platform.plans.PlanImageProcessor.ProcessedImage;
import com.obraviva.platform.storage.StorageException;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Imagen de un plano: el archivo del estudio de arquitectura, o una foto del plano impreso.
 *
 * <p>La subida sigue el patrón de todo el sistema: el navegador sube directo al
 * almacenamiento con una URL firmada y después confirma. La confirmación descarga el
 * archivo, lo valida y, si es un PDF, lo convierte a PNG. Ese trabajo corre fuera de
 * transacción: la base se toca en dos pasos cortos, antes y después.
 */
@Service
public class FloorPlanImageService {

    private static final Logger log = LoggerFactory.getLogger(FloorPlanImageService.class);

    /** Formatos aceptados y la extensión con que se guardan. */
    private static final Map<String, String> ACCEPTED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "application/pdf", "pdf");

    private final FloorPlanRepository planRepository;
    private final OrganizationMembershipService membershipService;
    private final StorageService storageService;
    private final PlanImageProcessor processor;
    private final TransactionTemplate transaction;

    public FloorPlanImageService(
            FloorPlanRepository planRepository,
            OrganizationMembershipService membershipService,
            StorageService storageService,
            PlanImageProcessor processor,
            TransactionTemplate transaction) {
        this.planRepository = planRepository;
        this.membershipService = membershipService;
        this.storageService = storageService;
        this.processor = processor;
        this.transaction = transaction;
    }

    public PlanImageUploadResponse requestUpload(UUID userId, UUID planId, RequestPlanImageUploadRequest request) {
        String extension = ACCEPTED.get(request.contentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "El plano tiene que ser JPG, PNG o PDF. Si lo tenés en DWG, exportalo a PDF desde el programa de dibujo.");
        }
        return transaction.execute(status -> {
            FloorPlan plan = requireOwnedPlan(userId, planId);
            String key = plan.prepareImageUpload(extension);
            return new PlanImageUploadResponse(storageService.presignUpload(key, request.contentType()), request.contentType());
        });
    }

    public FloorPlanResponse confirm(UUID userId, UUID planId) {
        String pendingKey = transaction.execute(status -> {
            FloorPlan plan = requireOwnedPlan(userId, planId);
            if (plan.getImagePendingStorageKey() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay una imagen de plano pendiente de confirmar");
            }
            return plan.getImagePendingStorageKey();
        });

        boolean pdf = pendingKey.endsWith(".pdf");
        Path downloaded = null;
        ProcessedImage processed = null;
        String finalKey;
        try {
            downloaded = Files.createTempFile("obraviva-plano-subido-", pdf ? ".pdf" : ".img");
            storageService.download(pendingKey, downloaded);
            processed = processor.process(downloaded, pdf);
            if (processed.file() != null) {
                finalKey = pendingKey.substring(0, pendingKey.lastIndexOf('.')) + ".png";
                storageService.upload(processed.file(), finalKey, "image/png");
            } else {
                finalKey = pendingKey;
            }
        } catch (InvalidPlanImageException e) {
            discardPending(userId, planId, pendingKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (StorageException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La imagen del plano no se encuentra en el almacenamiento");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo procesar el plano");
        } finally {
            deleteQuietly(downloaded);
            if (processed != null) {
                deleteQuietly(processed.file());
            }
        }

        ProcessedImage result = processed;
        FloorPlanResponse response = transaction.execute(status -> {
            FloorPlan plan = requireOwnedPlan(userId, planId);
            plan.confirmImage(finalKey, result.widthPx(), result.heightPx());
            return toResponse(plan);
        });
        if (pdf) {
            // El PDF original ya no se usa: se muestra el PNG. Si falla el borrado, queda un huérfano.
            deleteStoredQuietly(pendingKey);
        }
        log.info("Imagen del plano {} publicada por {} ({}x{}, desde {})",
                planId, userId, result.widthPx(), result.heightPx(), pdf ? "PDF" : "imagen");
        return response;
    }

    /**
     * Plano desde un PDF que ya está en el servidor, para los datos de demostración. Pasa por
     * la misma conversión que un plano subido.
     */
    public FloorPlanResponse importPdf(UUID userId, UUID planId, Path pdf) {
        ProcessedImage processed;
        try {
            processed = processor.process(pdf, true);
        } catch (InvalidPlanImageException e) {
            throw new IllegalStateException("El plano de ejemplo no se pudo convertir: " + e.getMessage(), e);
        }
        try {
            String key = transaction.execute(status -> requireOwnedPlan(userId, planId).prepareImageUpload("png"));
            storageService.upload(processed.file(), key, "image/png");
            return transaction.execute(status -> {
                FloorPlan plan = requireOwnedPlan(userId, planId);
                plan.confirmImage(key, processed.widthPx(), processed.heightPx());
                return toResponse(plan);
            });
        } finally {
            deleteQuietly(processed.file());
        }
    }

    public FloorPlanResponse removeImage(UUID userId, UUID planId) {
        return transaction.execute(status -> {
            FloorPlan plan = requireOwnedPlan(userId, planId);
            plan.removeImage();
            return toResponse(plan);
        });
    }

    /** La respuesta de la organización, con la imagen firmada con la vigencia larga del panel. */
    FloorPlanResponse toResponse(FloorPlan plan) {
        String url = plan.getImageStorageKey() == null ? null : storageService.presignDownload(plan.getImageStorageKey());
        return FloorPlanService.toResponse(plan, url);
    }

    private void discardPending(UUID userId, UUID planId, String pendingKey) {
        transaction.executeWithoutResult(status -> requireOwnedPlan(userId, planId).discardPendingImage());
        deleteStoredQuietly(pendingKey);
    }

    private FloorPlan requireOwnedPlan(UUID userId, UUID planId) {
        FloorPlan plan = planRepository
                .findByIdAndOrganizationIdIn(planId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Planta no encontrada"));
        if (!membershipService.isOwnerOf(userId, plan.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return plan;
    }

    private void deleteStoredQuietly(String key) {
        try {
            storageService.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar {} del almacenamiento: queda huérfano", key);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Un temporal que no se borra no afecta a nadie: el sistema lo limpia.
        }
    }
}
