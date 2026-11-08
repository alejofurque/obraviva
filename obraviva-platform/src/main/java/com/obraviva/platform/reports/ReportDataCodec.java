package com.obraviva.platform.reports;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el snapshot a texto JSON y de vuelta, para guardarlo en una sola
 * columna.
 *
 * <p>Mismo criterio que {@code ProposalCodec}: la serialización se aísla acá para
 * que el formato de almacenamiento no quede disperso entre el servicio, el worker y
 * la pantalla de revisión.
 */
@Component
public class ReportDataCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String encode(ReportData data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (RuntimeException e) {
            throw new ReportException("No se pudo serializar el snapshot del reporte", e);
        }
    }

    public ReportData decode(String json) {
        if (json == null || json.isBlank()) {
            throw new ReportException("El reporte no tiene snapshot almacenado");
        }
        try {
            return JSON.readValue(json, ReportData.class);
        } catch (RuntimeException e) {
            throw new ReportException("No se pudo leer el snapshot almacenado", e);
        }
    }
}