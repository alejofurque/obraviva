package com.obraviva.platform.ai.extraction;

import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte la propuesta a texto JSON y de vuelta, para guardarla en una sola
 * columna.
 *
 * <p>La serialización se aísla en una clase propia para que el formato de
 * almacenamiento no quede disperso entre el worker y los servicios de revisión. Si
 * algún día conviene pasar a {@code jsonb}, se cambia acá.
 */
public class ProposalCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String encode(ExtractionProposal proposal) {
        try {
            return JSON.writeValueAsString(proposal);
        } catch (RuntimeException e) {
            throw new ExtractionException("No se pudo serializar la propuesta", e);
        }
    }

    public ExtractionProposal decode(String json) {
        if (json == null || json.isBlank()) {
            return new ExtractionProposal(null, null, null);
        }
        try {
            return JSON.readValue(json, ExtractionProposal.class);
        } catch (RuntimeException e) {
            throw new ExtractionException("No se pudo leer la propuesta almacenada", e);
        }
    }
}