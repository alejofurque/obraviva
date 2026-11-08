package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ExtractionException;
import com.obraviva.platform.ai.extraction.ExtractionProposal;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.ExtractionValidator;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ProposalCodec;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.InvalidAudioException;
import com.obraviva.platform.ai.transcription.Transcription;
import com.obraviva.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Ejecuta el pipeline de interpretación de audio: transcribir e interpretar.
 *
 * <p>Corre en tres tiempos, igual que los demás workers: transacción corta para
 * tomar el trabajo, trabajo pesado sin transacción abierta, transacción corta para
 * registrar el desenlace. La transcripción de un audio de tres minutos tarda unos
 * veinticinco segundos y la interpretación depende de un servicio externo; ninguna
 * de las dos debe retener una conexión de base.
 *
 * <p>Al terminar la interpretación el trabajo queda en {@code NEEDS_REVIEW} y sale
 * de la cola: lo siguiente lo decide una persona, no un worker.
 */
@Component
public class AudioExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(AudioExtractionWorker.class);

    private final AudioExtractionService extractionService;
    private final StorageService storageService;
    private final AudioTranscriber transcriber;
    private final ProgressExtractor progressExtractor;
    private final ExtractionValidator validator;
    private final ProposalCodec proposalCodec;
    private final boolean enabled;

    public AudioExtractionWorker(
            AudioExtractionService extractionService,
            StorageService storageService,
            AudioTranscriber transcriber,
            ProgressExtractor progressExtractor,
            ExtractionValidator validator,
            ProposalCodec proposalCodec,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.extractionService = extractionService;
        this.storageService = storageService;
        this.transcriber = transcriber;
        this.progressExtractor = progressExtractor;
        this.validator = validator;
        this.proposalCodec = proposalCodec;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.poll-interval-ms:10000}",
            initialDelayString = "${obraviva.jobs.audio-initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            processNext();
        } catch (RuntimeException e) {
            // El planificador cancela la tarea si una ejecución propaga una excepción.
            log.error("Error inesperado en el ciclo de interpretación de audio", e);
        }
    }

    /** Procesa un trabajo si hay alguno pendiente. Expuesto para poder ejercitarlo en tests. */
    public void processNext() {
        Optional<AudioExtractionTask> task = extractionService.claimNext();
        task.ifPresent(t -> {
            switch (t.step()) {
                case TRANSCRIBE -> transcribe(t);
                case INTERPRET -> interpret(t);
                default -> log.warn("Paso inesperado en el worker de audio: {}", t.step());
            }
        });
    }

    // --- Transcripción ---

    private void transcribe(AudioExtractionTask task) {
        log.info("Transcribiendo el audio de la interpretación {} (trabajo {})",
                task.extractionId(), task.jobId());

        Path audio = null;
        try {
            audio = Files.createTempFile("obraviva-audio-", ".bin");
            storageService.download(task.audioKey(), audio);

            Transcription transcription = transcriber.transcribe(audio);
            extractionService.recordTranscript(
                    task.jobId(), task.extractionId(), transcription.text());

            log.info("Interpretación {}: {}", task.extractionId(), transcription.summary());

        } catch (InvalidAudioException e) {
            log.info("Audio de la interpretación {} rechazado: {}",
                    task.extractionId(), e.userMessage());
            extractionService.recordInvalidInput(
                    task.jobId(), task.extractionId(), e.userMessage());

        } catch (IOException e) {
            log.error("No se pudo crear el archivo temporal del trabajo {}", task.jobId(), e);
            extractionService.recordTechnicalFailure(
                    task.jobId(), task.extractionId(),
                    "Error de disco al preparar la transcripción: " + e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico transcribiendo la interpretación {}", task.extractionId(), e);
            extractionService.recordTechnicalFailure(
                    task.jobId(), task.extractionId(), String.valueOf(e.getMessage()));

        } finally {
            deleteQuietly(audio);
        }
    }

    // --- Interpretación ---

    private void interpret(AudioExtractionTask task) {
        log.info("Interpretando la transcripción de {} ({} etapas disponibles)",
                task.extractionId(), task.availableStages().size());

        if (task.transcript() == null || task.transcript().isBlank()) {
            // No debería ocurrir: el paso previo guarda la transcripción antes de avanzar.
            extractionService.recordInvalidInput(task.jobId(), task.extractionId(),
                    "No hay transcripción para interpretar");
            return;
        }

        try {
            AudioExtraction raw = progressExtractor.extract(
                    new ExtractionRequest(task.transcript(), task.availableStages()));

            // La validación depura contra el dominio antes de que nada llegue al
            // revisor: identificadores inventados, estados inexistentes, textos vacíos.
            AudioExtraction validated = validator.validate(raw, task.availableStages());
            String proposalJson = proposalCodec.encode(ExtractionProposal.from(validated));

            extractionService.recordProposal(
                    task.jobId(),
                    task.extractionId(),
                    task.transcript(),
                    proposalJson,
                    validated.rawOutput(),
                    validated.promptVersion(),
                    validated.modelId());

            log.info("Interpretación {} lista con {} elementos propuestos",
                    task.extractionId(), validated.elementCount());

                } catch (ExtractionException e) {
            // La respuesta cruda se guarda aunque el análisis haya fallado: sin ella
            // no hay forma de diagnosticar después qué devolvió el modelo.
            if (e.hasRawOutput()) {
                extractionService.recordFailedOutput(
                        task.jobId(), task.extractionId(), e.rawOutput(), e.getMessage());
            } else {
                extractionService.recordTechnicalFailure(
                        task.jobId(), task.extractionId(), e.getMessage());
            }
            log.error("Fallo interpretando la transcripción de {}: {}",
                    task.extractionId(), e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo interpretando la transcripción de {}", task.extractionId(), e);
            extractionService.recordTechnicalFailure(
                    task.jobId(), task.extractionId(), String.valueOf(e.getMessage()));
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("No se pudo borrar el archivo temporal {}", path, e);
        }
    }
}