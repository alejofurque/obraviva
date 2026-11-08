package com.obraviva.platform.ai.extraction;

import java.util.UUID;

/**
 * Etapa existente del desarrollo, tal como se le presenta al modelo.
 *
 * <p>El modelo recibe la lista completa de etapas y sólo puede referenciar estos
 * identificadores. No inventa etapas: eso convertiría la propuesta en algo que el
 * revisor no puede reconocer, y rompería la trazabilidad contra el dominio.
 */
public record StageContext(
        UUID stageId,
        String name,
        int orderIndex,
        String currentStatus) {
}