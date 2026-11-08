package com.obraviva.platform.ai.extraction;

/**
 * Alerta sobre insumos o materiales mencionada en el audio: faltantes, demoras de
 * proveedores, compras a confirmar.
 *
 * <p>No tiene etapa asociada ni cambia estado alguno: es información para que el
 * responsable actúe. Se separa de las novedades porque exige una decisión distinta.
 */
public record SupplyAlert(
        String description,
        ConfidenceLevel confidence) {
}