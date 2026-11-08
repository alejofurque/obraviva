package com.obraviva.platform.ai.transcription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Adaptador simulado de transcripción: no escucha el audio y siempre devuelve el mismo
 * texto. El texto lo dice, porque la persona lo ve en la pantalla de revisión, y nombra
 * etapas y un pedido de material para que el simulador de interpretación, que trabaja por
 * palabras clave, tenga algo que proponer.
 */
public class SimulatedAudioTranscriber implements AudioTranscriber {

    private static final Logger log = LoggerFactory.getLogger(SimulatedAudioTranscriber.class);

    public static final String TEXT = "Transcripción simulada, no es lo que dice el audio. "
            + "Seguimos con la mampostería de planta baja y arrancamos la instalación eléctrica. "
            + "Hay que hacer el pedido de material para la semana que viene.";

    @Override
    public Transcription transcribe(Path audioFile) {
        log.info("[MOCK] Transcripción simulada de {}", audioFile.getFileName());
        return new Transcription(TEXT, Duration.ZERO, Duration.ZERO);
    }
}
