package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.ExtractionProposal;
import com.obraviva.platform.progress.CreateProgressEntryRequest.StageUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AudioDtos {

    private AudioDtos() {
    }

    // --- Alta ---

    /** {@code unitId}: la casa sobre la que se publica el parte. Sin casa, las obras comunes. */
    public record CreateAudioExtractionRequest(
            @NotNull @Size(max = 300) String audioFilename,
            @NotNull @Size(max = 100) String audioContentType,
            UUID unitId) {

        public CreateAudioExtractionRequest(String audioFilename, String audioContentType) {
            this(audioFilename, audioContentType, null);
        }
    }

    public record AudioExtractionJobResponse(
            UUID extractionId,
            UUID jobId,
            String reviewStatus,
            String uploadUrl,
            String contentType) {
    }

    // --- Revisión ---

    /**
     * Detalle completo para la pantalla de revisión.
     *
     * <p>Incluye la transcripción junto a la propuesta, como pide el documento
     * maestro: el revisor tiene que poder contrastar lo que dijo el encargado con lo
     * que el modelo interpretó, y corregir sobre eso.
     */
    public record ExtractionDetailResponse(
            UUID id,
            UUID developmentId,
            String reviewStatus,
            String transcript,
            ExtractionProposal proposal,
            String promptVersion,
            String modelId,
            String audioUrl,
            Instant createdAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNotes,
            UUID unitId) {
    }

    /**
     * Resumen para los listados. {@code jobStatus} y {@code pipelineStep} dicen en qué paso
     * está un audio que todavía se procesa (transcribir, ordenar): nulos si no tiene
     * trabajo. {@code mine}: lo subió quien mira.
     */
    public record ExtractionSummaryResponse(
            UUID id,
            UUID developmentId,
            String reviewStatus,
            String transcriptPreview,
            int proposedElements,
            Instant createdAt,
            UUID unitId,
            String jobStatus,
            String pipelineStep,
            String reviewNotes,
            boolean mine) {
    }

    /**
     * Confirmación del revisor: los avances que quedaron después de revisar, cada uno
     * como un "Publicar avance". Una novedad y el cambio de estado de su etapa van juntos;
     * un cambio de estado sin novedad lleva el texto que el revisor dejó. La IA propone
     * estados, nunca porcentajes: {@code stageUpdate.progressPercent} lo pone la persona.
     *
     * <p>Lo que llega del navegador no es confiable: cada avance pasa por las mismas
     * validaciones que el alta manual (casa, etapa de la casa, fecha no futura, fotos
     * propias y pendientes), y si una falla no se publica ninguno. Las alertas de
     * insumos no viajan: se toman de la propuesta guardada y no se publican.
     */
    public record ApproveExtractionRequest(
            @NotNull LocalDate entryDate,
            @NotNull @Size(max = 30) List<@Valid @NotNull ApprovedEntry> entries,
            @Size(max = 1000) String notes) {
    }

    /**
     * Un avance aprobado. {@code photoSectors} dice el lugar de la casa de cada foto, por su
     * id, como en "Publicar avance"; la que no figura queda en "General".
     */
    public record ApprovedEntry(
            UUID stageId,
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 10) List<UUID> photoIds,
            @Valid StageUpdate stageUpdate,
            @Size(max = 10) Map<UUID, @Size(max = 80) String> photoSectors) {
    }

    public record RejectExtractionRequest(
            @Size(max = 1000) String notes) {
    }

    public record ApprovalResultResponse(
            UUID extractionId,
            String reviewStatus,
            int publishedEntries,
            int updatedStages) {
    }
}