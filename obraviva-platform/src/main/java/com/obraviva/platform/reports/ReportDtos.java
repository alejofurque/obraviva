package com.obraviva.platform.reports;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * Alta de un reporte.
     *
     * <p>El período es un rango explícito y no un mes: la restricción mensual es de
     * presentación, no del modelo. La interfaz propondrá el mes anterior por omisión.
     */
    public record GenerateReportRequest(
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd) {
    }

    /**
     * Detalle completo para la pantalla de revisión: el snapshot junto al texto, de
     * modo que el revisor pueda contrastar cada afirmación contra el dato que la
     * originó.
     */
    public record ReportDetailResponse(
            UUID id,
            UUID developmentId,
            String status,
            LocalDate periodStart,
            LocalDate periodEnd,
            ReportData data,
            String narrative,
            String promptVersion,
            String modelId,
            Instant createdAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNotes) {
    }

    /** Resumen para el listado. */
    public record ReportSummaryResponse(
            UUID id,
            UUID developmentId,
            String status,
            LocalDate periodStart,
            LocalDate periodEnd,
            int progressEntries,
            int captures,
            Instant createdAt) {
    }

    /**
     * Aprobación del revisor.
     *
     * <p>El texto viaja en la petición porque el revisor pudo haberlo corregido: lo
     * que queda emitido es lo que él dejó, no lo que el modelo escribió. La versión
     * original permanece en {@code rawOutput}.
     *
     * <p>A diferencia de la redacción del modelo, este texto <strong>no</strong> pasa
     * por la salvaguarda de cifras. La red vigila al modelo, no a la persona: si el
     * revisor escribe un número que no está en el snapshot es porque sabe algo que el
     * sistema no, y ésa es precisamente su autoridad.
     */
    public record ApproveReportRequest(
            @NotBlank @Size(max = 20000) String narrative,
            @Size(max = 1000) String notes) {
    }

    public record RejectReportRequest(
            @Size(max = 1000) String notes) {
    }
}