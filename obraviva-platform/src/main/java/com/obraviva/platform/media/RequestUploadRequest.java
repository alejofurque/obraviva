package com.obraviva.platform.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Alta de una foto o video. Con {@code progressEntryId}, es evidencia de ese avance y hereda
 * su casa. Sin avance y con {@code unitId}, es de la galería de la casa: lo que se muestra
 * para vender. Sin ninguno de los dos, es del desarrollo.
 *
 * <p>{@code sector} es el lugar de la casa que muestra, uno de sus sectores; nulo es
 * "General". Una foto de obras comunes no lleva sector. Una foto que se publica con un
 * avance recibe su sector al publicarlo ({@code photoSectors}), no acá.
 *
 * <p>{@code showcase} dice si es de la galería de venta. Si no viene, se infiere como antes:
 * de una casa y sin avance es galería. La sesión de recorrido manda {@code false} y
 * {@code takenOn}, el día de la sesión, que no puede ser futuro.
 */
public record RequestUploadRequest(
        @NotBlank @Size(max = 300) String filename,
        @NotBlank @Size(max = 100) String contentType,
        UUID progressEntryId,
        UUID unitId,
        @Size(max = 80) String sector,
        Boolean showcase,
        LocalDate takenOn) {

    public RequestUploadRequest(String filename, String contentType, UUID progressEntryId) {
        this(filename, contentType, progressEntryId, null, null, null, null);
    }

    public RequestUploadRequest(String filename, String contentType, UUID progressEntryId, UUID unitId) {
        this(filename, contentType, progressEntryId, unitId, null, null, null);
    }
}
