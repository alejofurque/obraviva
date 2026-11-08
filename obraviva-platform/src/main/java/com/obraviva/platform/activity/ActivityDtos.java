package com.obraviva.platform.activity;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Las novedades de un desarrollo, para el dueño: lo que publicaron los encargados, del más
 * nuevo al más viejo. Es lectura: lo publicado ya lo ve el comprador, esto es para revisarlo
 * y corregirlo si hace falta, no para aprobarlo.
 */
public final class ActivityDtos {

    private ActivityDtos() {
    }

    /**
     * Una página del feed. {@code nextCursor} se manda como {@code before} para la siguiente;
     * nulo si no hay más. {@code seenUntil} es hasta dónde vio quien mira, nulo si nunca entró
     * o si no es el dueño.
     */
    public record ActivityPage(List<ActivityItem> items, Instant nextCursor, Instant seenUntil) {
    }

    /**
     * Un ítem. {@code at} es el momento que lo ordena: la primera publicación de un avance, la
     * última foto de una sesión, el alta de un recorrido, la aprobación del audio que avisó un
     * faltante de materiales. {@code unitCode} es nulo en las obras
     * comunes. {@code unseen} sólo se calcula para el dueño. Según {@code kind}, viene uno de
     * {@code entry}, {@code session}, {@code capture} o {@code supply}.
     */
    public record ActivityItem(
            String kind,
            String id,
            Instant at,
            UUID unitId,
            String unitCode,
            UUID authorId,
            String authorName,
            Boolean unseen,
            EntryActivity entry,
            SessionActivity session,
            CaptureActivity capture,
            SupplyActivity supply) {
    }

    /**
     * Un avance, con su versión vigente. {@code entryId} es esa versión, la que se corrige.
     * {@code correctedByName} y {@code correctedAt} vienen si tuvo correcciones.
     */
    public record EntryActivity(
            UUID entryId,
            UUID rootId,
            LocalDate entryDate,
            String stageName,
            String source,
            String description,
            String correctedByName,
            Instant correctedAt,
            List<ActivityPhoto> photos,
            StageChangeView stageChange) {
    }

    /** Lo que el avance le hizo a su etapa. Los avances anteriores a V32 no lo tienen. */
    public record StageChangeView(Integer percentBefore, Integer percentAfter, String statusBefore, String statusAfter) {
    }

    /** Las fotos y videos que un encargado subió de una casa en una sesión (un día). */
    public record SessionActivity(LocalDate day, Integer photos, Integer videos, List<String> sectors,
                                  List<ActivityPhoto> thumbnails) {
    }

    /**
     * Una foto o video del feed. {@code url} es el original; {@code thumbUrl} y {@code screenUrl}
     * sus versiones livianas (en un video, el fotograma de portada), nulas mientras no estén.
     */
    public record ActivityPhoto(String url, String thumbUrl, String screenUrl, String kind, String sector) {
    }

    /** Un recorrido 3D: listo, reconstruyendo o fallido con su motivo. */
    public record CaptureActivity(UUID captureId, LocalDate captureDate, String zone, String state, String failureReason) {
    }

    /**
     * Los avisos de materiales de un audio aprobado ("falta el tablero eléctrico"), tal como
     * quedaron en la propuesta: no se publican, le llegan al dueño. Sólo lectura.
     */
    public record SupplyActivity(UUID extractionId, List<String> alerts) {
    }

    /** Un audio por revisar, con lo que propone: "2 novedades, 1 cambio de etapa". */
    public record PendingAudio(UUID extractionId, UUID unitId, String unitCode, UUID authorId, String authorName,
                               Instant createdAt, Integer progressNotes, Integer stageChanges) {
    }

    /** Cuántas novedades hay sin ver; {@code more} si son más que las contadas. */
    public record UnseenCount(Integer count, Boolean more) {
    }

    /** Hasta dónde vio: el ítem más nuevo que se le mostró, no "ahora". */
    public record MarkSeenRequest(@NotNull Instant until) {
    }
}
