package com.obraviva.platform.captures3d;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class CaptureDtos {

    private CaptureDtos() {
    }

    public record Vector3(double x, double y, double z) {
    }

    public record TransformDto(
            @NotNull Vector3 position,
            @NotNull Vector3 rotationDeg,
            @Positive double scale) {

        static TransformDto from(SpatialTransform transform) {
            return new TransformDto(
                    new Vector3(
                            transform.getPositionX(),
                            transform.getPositionY(),
                            transform.getPositionZ()),
                    new Vector3(
                            transform.getRotationX(),
                            transform.getRotationY(),
                            transform.getRotationZ()),
                    transform.getScale());
        }

        SpatialTransform toEntity() {
            return new SpatialTransform(
                    position.x(), position.y(), position.z(),
                    rotationDeg.x(), rotationDeg.y(), rotationDeg.z(),
                    scale);
        }
    }

    /**
     * Alta de un SPZ ya reconstruido. {@code filename} y {@code fileSize} son lo que declara
     * el cliente antes de subir: si vienen, se rechaza ahí un archivo que no es .spz o que
     * pasa el máximo, sin crear nada. La validación que manda es la de la confirmación, que
     * mide el archivo subido de verdad.
     */
    public record CreateCaptureRequest(
            @NotNull LocalDate captureDate,
            @Size(max = 500) String description,
            UUID unitId,
            UUID stageId,
            @Size(max = 80) String zone,
            @Size(max = 300) String filename,
            Long fileSize) {

        public CreateCaptureRequest(LocalDate captureDate, String description, UUID unitId, UUID stageId) {
            this(captureDate, description, unitId, stageId, null, null, null);
        }

        public CreateCaptureRequest(LocalDate captureDate, String description, UUID unitId, UUID stageId, String zone) {
            this(captureDate, description, unitId, stageId, zone, null, null);
        }
    }

    /**
     * Corrección de los datos descriptivos de una captura. Reemplaza los cuatro: lo que
     * no viene queda vacío, salvo la fecha, que toda captura tiene.
     */
    public record UpdateCaptureRequest(
            @NotNull LocalDate captureDate,
            @Size(max = 500) String description,
            @Size(max = 80) String zone,
            UUID stageId) {
    }

    public record UpdateTransformRequest(@NotNull @Valid TransformDto transform) {
    }

    public record CaptureUploadResponse(UUID captureId, String uploadUrl, String contentType) {
    }

    public record CaptureResponse(
            UUID id,
            UUID developmentId,
            UUID unitId,
            UUID stageId,
            LocalDate captureDate,
            String description,
            String zone,
            CaptureStatus status,
            TransformDto transform,
            String splatUrl) {

        static CaptureResponse from(Capture3d capture, String splatUrl) {
            return new CaptureResponse(
                    capture.getId(),
                    capture.getDevelopmentId(),
                    capture.getUnitId(),
                    capture.getStageId(),
                    capture.getCaptureDate(),
                    capture.getDescription(),
                    capture.getZone(),
                    capture.getStatus(),
                    TransformDto.from(capture.getTransform()),
                    splatUrl);
        }
    }
        public record CreateCaptureFromVideoRequest(
            @NotNull LocalDate captureDate,
            @Size(max = 500) String description,
            UUID unitId,
            UUID stageId,
            @NotNull @Size(max = 300) String videoFilename,
            @NotNull @Size(max = 100) String videoContentType,
            @Size(max = 80) String zone) {

        public CreateCaptureFromVideoRequest(
                LocalDate captureDate, String description, UUID unitId, UUID stageId,
                String videoFilename, String videoContentType) {
            this(captureDate, description, unitId, stageId, videoFilename, videoContentType, null);
        }
    }

    public record CaptureJobResponse(
            UUID captureId,
            UUID jobId,
            String jobStatus,
            String uploadUrl,
            String contentType) {
    }

    /**
     * Captura que todavía no está disponible, con el punto del pipeline donde está.
     *
     * <p>{@code failureReason} sólo se completa si el trabajo falló. Un trabajo
     * reencolado también guarda su último error, pero mostrarlo mientras el sistema
     * sigue reintentando haría parecer fallida una captura que puede terminar bien.
     *
     * <p>{@code mine} dice si la subió quien consulta: el encargado sólo descarta lo suyo.
     */
    public record CaptureProcessingResponse(
            UUID captureId,
            UUID unitId,
            UUID stageId,
            LocalDate captureDate,
            String description,
            String zone,
            Instant createdAt,
            String jobStatus,
            String pipelineStep,
            Instant stepStartedAt,
            String failureReason,
            boolean mine) {
    }
}