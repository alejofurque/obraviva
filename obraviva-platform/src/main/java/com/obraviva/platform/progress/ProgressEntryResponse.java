package com.obraviva.platform.progress;

import com.obraviva.platform.progress.ProgressService.StageChange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un avance. {@code authorName} es quien publicó la primera versión del hilo y
 * {@code stageChange}, lo que esa publicación le hizo a la etapa (V32): una corrección no
 * cambia ni el autor ni la etapa. Los resuelve la línea de tiempo; en las respuestas de alta
 * y corrección el autor viene nulo.
 */
public record ProgressEntryResponse(
        UUID id,
        UUID developmentId,
        UUID unitId,
        UUID stageId,
        LocalDate entryDate,
        String description,
        ProgressSource source,
        int version,
        UUID rootId,
        boolean current,
        Instant createdAt,
        String authorName,
        StageChange stageChange) {

    static ProgressEntryResponse from(ProgressEntry entry) {
        return from(entry, null, ProgressService.stageChangeOf(entry));
    }

    static ProgressEntryResponse from(ProgressEntry entry, String authorName, StageChange stageChange) {
        return new ProgressEntryResponse(
                entry.getId(),
                entry.getDevelopmentId(),
                entry.getUnitId(),
                entry.getStageId(),
                entry.getEntryDate(),
                entry.getDescription(),
                entry.getSource(),
                entry.getVersion(),
                entry.getRootId(),
                entry.isCurrent(),
                entry.getCreatedAt(),
                authorName,
                stageChange);
    }
}
