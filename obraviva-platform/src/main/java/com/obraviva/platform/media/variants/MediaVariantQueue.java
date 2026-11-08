package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaAsset;
import com.obraviva.platform.media.MediaAssetRepository;
import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.media.MediaStatus;
import com.obraviva.platform.media.MediaVariantsStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Los pasos con base de datos de la cola de versiones livianas, cada uno su transacción
 * corta. Están en un bean aparte de {@link MediaVariantService} para que las transacciones
 * se abran de verdad: llamadas dentro del mismo bean no pasan por el proxy de Spring.
 */
@Component
class MediaVariantQueue {

    private static final Logger log = LoggerFactory.getLogger(MediaVariantQueue.class);

    private final MediaAssetRepository mediaRepository;
    private final ImageVariantRepository imageRepository;
    private final int maxAttempts;

    MediaVariantQueue(MediaAssetRepository mediaRepository, ImageVariantRepository imageRepository,
                      @Value("${obraviva.jobs.max-attempts:3}") int maxAttempts) {
        this.mediaRepository = mediaRepository;
        this.imageRepository = imageRepository;
        this.maxAttempts = maxAttempts;
    }

    /** Lo que hace falta para trabajar una foto fuera de la transacción. */
    record Work(UUID id, String storageKey, MediaKind kind, String thumbKey, String screenKey) {
    }

    /** Los próximos de la cola, de lo más viejo a lo más nuevo. */
    @Transactional(readOnly = true)
    public List<UUID> nextPending() {
        return mediaRepository
                .findTop5ByVariantsStatusAndStatusOrderByCreatedAtAsc(MediaVariantsStatus.PENDING, MediaStatus.AVAILABLE)
                .stream().map(MediaAsset::getId).toList();
    }

    /** Cuenta el intento antes de empezar: si el proceso se corta, ya quedó contado. */
    @Transactional
    public Optional<Work> start(UUID mediaId) {
        return mediaRepository.findById(mediaId)
                .filter(asset -> asset.getVariantsStatus() == MediaVariantsStatus.PENDING && asset.isAvailable())
                .map(asset -> {
                    asset.startVariantsAttempt();
                    return new Work(asset.getId(), asset.getStorageKey(), asset.getKind(),
                            asset.variantKey("thumb"), asset.variantKey("screen"));
                });
    }

    @Transactional
    public void ready(UUID mediaId, String thumbKey, String screenKey) {
        // Si la foto se borró mientras tanto, las versiones quedan huérfanas: no rompen nada.
        mediaRepository.findById(mediaId).ifPresent(asset -> asset.variantsReady(thumbKey, screenKey));
    }

    // --- Portadas y renders (image_variants): los mismos pasos, con la clave del original como id ---

    /** Lo que hace falta para trabajar una imagen fuera de la transacción. */
    record ImageWork(String storageKey, String thumbKey, String screenKey) {
    }

    @Transactional(readOnly = true)
    public List<String> nextPendingImages() {
        return imageRepository.findTop5ByStatusOrderByCreatedAtAsc(MediaVariantsStatus.PENDING)
                .stream().map(ImageVariant::getStorageKey).toList();
    }

    /** Cuenta el intento antes de empezar, como con las fotos. */
    @Transactional
    public Optional<ImageWork> startImage(String storageKey) {
        return imageRepository.findById(storageKey)
                .filter(variant -> variant.getStatus() == MediaVariantsStatus.PENDING)
                .map(variant -> {
                    variant.startAttempt();
                    return new ImageWork(storageKey, ImageVariant.variantKey(storageKey, "thumb"),
                            ImageVariant.variantKey(storageKey, "screen"));
                });
    }

    /** Si la imagen se descartó mientras tanto, las versiones quedan huérfanas: no rompen nada. */
    @Transactional
    public void imageReady(String storageKey, String thumbKey, String screenKey) {
        imageRepository.findById(storageKey).ifPresent(variant -> variant.ready(thumbKey, screenKey));
    }

    @Transactional
    public void imageFailed(String storageKey, Exception error) {
        imageRepository.findById(storageKey).ifPresent(variant -> {
            if (variant.attemptFailed(maxAttempts)) {
                log.error("Sin versiones livianas para la imagen {} tras {} intentos; se muestra el original: {}",
                        storageKey, variant.getAttempts(), error.getMessage());
            } else {
                log.warn("Falló el intento {} de versiones livianas de la imagen {}: {}",
                        variant.getAttempts(), storageKey, error.getMessage());
            }
        });
    }

    @Transactional
    public void failed(UUID mediaId, Exception error) {
        mediaRepository.findById(mediaId).ifPresent(asset -> {
            if (asset.variantsAttemptFailed(maxAttempts)) {
                log.error("Sin versiones livianas para el medio {} tras {} intentos; se muestra el original: {}",
                        mediaId, asset.getVariantsAttempts(), error.getMessage());
            } else {
                log.warn("Falló el intento {} de versiones livianas del medio {}: {}",
                        asset.getVariantsAttempts(), mediaId, error.getMessage());
            }
        });
    }
}
