package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.storage.StorageService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Las versiones livianas de una foto: bajar el original, achicarlo con ffmpeg y subir las
 * dos versiones. Pasa fuera de toda transacción: una foto pesada no deja la base trabada.
 * El estado vive en la propia foto y lo maneja {@link MediaVariantQueue}.
 *
 * <p>Se puede cortar y retomar sin hacer nada: lo pendiente queda pendiente. Al tercer
 * intento fallido la foto queda FAILED, con log, y se sigue viendo con el original. Las
 * portadas y los renders ({@link ImageVariants}) pasan por los mismos pasos.
 */
@Service
public class MediaVariantService {

    private final MediaVariantQueue queue;
    private final StorageService storageService;
    private final MediaVariantGenerator generator;

    MediaVariantService(MediaVariantQueue queue, StorageService storageService, MediaVariantGenerator generator) {
        this.queue = queue;
        this.storageService = storageService;
        this.generator = generator;
    }

    public List<UUID> nextPending() {
        return queue.nextPending();
    }

    public List<String> nextPendingImages() {
        return queue.nextPendingImages();
    }

    /**
     * Procesa una portada o un render de la cola ({@link ImageVariants}), con los mismos pasos
     * que una foto. Devuelve si quedó lista. Un fallo nunca sale de acá.
     */
    public boolean processImage(String storageKey) {
        Optional<MediaVariantQueue.ImageWork> started = queue.startImage(storageKey);
        if (started.isEmpty()) {
            return false;
        }
        MediaVariantQueue.ImageWork work = started.get();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("obraviva-variants-");
            Path original = dir.resolve("original");
            storageService.download(work.storageKey(), original);
            MediaVariantGenerator.GeneratedVariants variants = generator.generate(original, MediaKind.PHOTO, dir);
            storageService.upload(variants.thumb(), work.thumbKey(), "image/jpeg");
            storageService.upload(variants.screen(), work.screenKey(), "image/jpeg");
            queue.imageReady(storageKey, work.thumbKey(), work.screenKey());
            return true;
        } catch (IOException | RuntimeException e) {
            queue.imageFailed(storageKey, e);
            return false;
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Procesa una foto de la cola. Devuelve si quedó lista. Un fallo nunca sale de acá. */
    public boolean process(UUID mediaId) {
        Optional<MediaVariantQueue.Work> started = queue.start(mediaId);
        if (started.isEmpty()) {
            return false;
        }
        MediaVariantQueue.Work work = started.get();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("obraviva-variants-");
            Path original = dir.resolve("original");
            storageService.download(work.storageKey(), original);
            MediaVariantGenerator.GeneratedVariants variants = generator.generate(original, work.kind(), dir);
            storageService.upload(variants.thumb(), work.thumbKey(), "image/jpeg");
            storageService.upload(variants.screen(), work.screenKey(), "image/jpeg");
            queue.ready(mediaId, work.thumbKey(), work.screenKey());
            return true;
        } catch (IOException | RuntimeException e) {
            queue.failed(mediaId, e);
            return false;
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // Un temporal que queda no afecta a nada.
        }
    }
}
