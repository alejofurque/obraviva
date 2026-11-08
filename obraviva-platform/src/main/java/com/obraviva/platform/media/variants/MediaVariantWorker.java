package com.obraviva.platform.media.variants;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Toma la cola de versiones livianas cada pocos segundos, de a unas pocas fotos: las nuevas
 * apenas se confirman y las que ya existían, con el mismo mecanismo y sin proceso aparte.
 * Después de las fotos y videos ({@code media_assets}) toma las portadas y los renders
 * ({@code image_variants}).
 * Se apaga con los demás workers ({@code obraviva.jobs.enabled}).
 */
@Component
public class MediaVariantWorker {

    private final MediaVariantService variantService;
    private final boolean enabled;

    public MediaVariantWorker(MediaVariantService variantService, @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.variantService = variantService;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.variants-poll-interval-ms:5000}",
            initialDelayString = "${obraviva.jobs.variants-initial-delay-ms:40000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        processPending();
    }

    /**
     * Procesa lo que hay en la cola ahora: primero las fotos y videos, después las portadas y
     * los renders. Devuelve cuántas quedaron listas. Lo usan los tests.
     */
    public int processPending() {
        int ready = 0;
        for (UUID mediaId : variantService.nextPending()) {
            if (variantService.process(mediaId)) {
                ready++;
            }
        }
        for (String storageKey : variantService.nextPendingImages()) {
            if (variantService.processImage(storageKey)) {
                ready++;
            }
        }
        return ready;
    }
}
