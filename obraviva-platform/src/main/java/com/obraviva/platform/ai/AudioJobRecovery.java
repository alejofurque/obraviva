package com.obraviva.platform.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Al arrancar, devuelve a la cola los trabajos de audio que un corte dejó tomados. Sin esto,
 * reiniciar el backend mientras se transcribe dejaba la extracción en PENDING para siempre:
 * la cola sólo toma trabajos PENDING.
 *
 * <p>Sólo audio: los recorridos 3D dependen de un servicio externo con su propio seguimiento.
 * Se apaga con los workers ({@code obraviva.jobs.enabled}), porque sin ellos no hay quién
 * retome el trabajo.
 */
@Component
public class AudioJobRecovery {

    private final AudioExtractionService extractionService;
    private final boolean enabled;

    public AudioJobRecovery(
            AudioExtractionService extractionService,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.extractionService = extractionService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        if (enabled) {
            extractionService.recoverInterruptedJobs();
        }
    }
}
