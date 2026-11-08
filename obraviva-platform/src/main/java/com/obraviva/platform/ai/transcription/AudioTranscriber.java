package com.obraviva.platform.ai.transcription;

import java.nio.file.Path;

/**
 * Pasa un audio a texto. Dos adaptadores: {@link WhisperAudioTranscriber}, local, y
 * {@link SimulatedAudioTranscriber}, para probar el circuito de audios sin instalar
 * ffmpeg ni descargar el modelo.
 */
public interface AudioTranscriber {

    /**
     * Transcribe el audio indicado.
     *
     * @throws InvalidAudioException si el audio no sirve (mensaje apto para el usuario)
     * @throws TranscriptionException si falla la herramienta o falta el modelo
     */
    Transcription transcribe(Path audioFile);
}
