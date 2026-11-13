package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaVariantsStatus;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API pública de las versiones livianas de las imágenes que no son medios de obra: la portada
 * y el render del frente de una casa, el render de una comparación y la portada del desarrollo.
 * Cada módulo guarda la clave de su imagen en su tabla y avisa acá cuándo nace y cuándo se va.
 *
 * <ul>
 *   <li>{@link #enqueue}: al confirmar una imagen. La pone en la cola del worker, o salteada si
 *       es SVG.</li>
 *   <li>{@link #discard}: al reemplazarla o borrarla. Borra su fila y, después de confirmada la
 *       transacción, sus versiones del almacenamiento. El original no se toca: lo decide cada
 *       módulo, y hoy un reemplazo lo conserva.</li>
 *   <li>{@link #lookup}: en las proyecciones, las versiones de varias imágenes en una consulta,
 *       con el original de respaldo mientras no estén.</li>
 * </ul>
 */
@Service
public class ImageVariants {

    private static final Logger log = LoggerFactory.getLogger(ImageVariants.class);

    private final ImageVariantRepository repository;
    private final StorageService storageService;

    ImageVariants(ImageVariantRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    /** Una imagen firmada: el original y sus versiones, nulas mientras el worker no las generó. */
    public record SignedImage(String url, String thumbUrl, String screenUrl) {
    }

    /** Entra a la cola una imagen recién confirmada. Si ya estaba, no hace nada. Sin clave, tampoco. */
    @Transactional
    public void enqueue(UUID organizationId, String storageKey) {
        if (storageKey == null || repository.existsById(storageKey)) {
            return;
        }
        repository.save(new ImageVariant(organizationId, storageKey));
    }

    /**
     * La imagen dejó de usarse (se reemplazó o se borró): se va su fila y, cuando la
     * transacción se confirma, sus versiones. Borrar antes y que después falle la transacción
     * dejaría la imagen vigente sin versiones. Sin clave, o sin fila, no hace nada.
     */
    @Transactional
    public void discard(UUID organizationId, String storageKey) {
        if (storageKey == null) {
            return;
        }
        repository.findByOrganizationIdInAndStorageKey(List.of(organizationId), storageKey).ifPresent(variant -> {
            repository.delete(variant);
            List<String> keys = Stream.of(variant.getThumbStorageKey(), variant.getScreenStorageKey())
                    .filter(Objects::nonNull).toList();
            if (!keys.isEmpty()) {
                afterCommit(() -> keys.forEach(this::deleteQuietly));
            }
        });
    }

    /** Las versiones de varias imágenes de las organizaciones dadas, en una sola consulta. */
    @Transactional(readOnly = true)
    public Images lookup(Collection<UUID> organizationIds, Collection<String> storageKeys) {
        List<String> keys = storageKeys.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, ImageVariant> ready = keys.isEmpty() || organizationIds.isEmpty() ? Map.of()
                : repository.findByOrganizationIdInAndStorageKeyIn(organizationIds, keys).stream()
                        .filter(variant -> variant.getStatus() == MediaVariantsStatus.READY)
                        .collect(Collectors.toMap(ImageVariant::getStorageKey, Function.identity()));
        return new Images(ready, storageService);
    }

    /** Lo que devolvió {@link #lookup}: firma cada imagen con sus versiones si están listas. */
    public static final class Images {

        private final Map<String, ImageVariant> ready;
        private final StorageService storageService;

        private Images(Map<String, ImageVariant> ready, StorageService storageService) {
            this.ready = ready;
            this.storageService = storageService;
        }

        /** Firmada con la duración por omisión; nula si no hay imagen. */
        public SignedImage sign(String storageKey) {
            return sign(storageKey, storageService::presignDownload);
        }

        /** Firmada con una duración propia, como la del enlace público. */
        public SignedImage sign(String storageKey, Duration ttl) {
            return sign(storageKey, key -> storageService.presignDownload(key, ttl));
        }

        private SignedImage sign(String storageKey, Function<String, String> presign) {
            if (storageKey == null) {
                return null;
            }
            ImageVariant variant = ready.get(storageKey);
            return new SignedImage(
                    presign.apply(storageKey),
                    variant == null ? null : presign.apply(variant.getThumbStorageKey()),
                    variant == null ? null : presign.apply(variant.getScreenStorageKey()));
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void deleteQuietly(String key) {
        try {
            storageService.delete(key);
        } catch (RuntimeException e) {
            // Una versión que queda en el almacenamiento no se muestra en ningún lado.
            log.warn("No se pudo borrar la versión {}: {}", key, e.getMessage());
        }
    }
}
