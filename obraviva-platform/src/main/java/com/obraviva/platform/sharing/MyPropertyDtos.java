package com.obraviva.platform.sharing;

import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitView;

import java.util.UUID;

public final class MyPropertyDtos {

    private MyPropertyDtos() {
    }

    /** Cada unidad del comprador, para elegir cuando tiene más de una. */
    public record MyUnitSummary(
            UUID unitId,
            String unitCode,
            String unitDescription,
            String developmentName,
            String developmentAddress,
            String developmentKind) {
    }

    /**
     * "Mi propiedad": la vista de la unidad más lo que el comprador puede hacer con
     * ella. El identificador sí viaja acá, a diferencia de la vista pública: hay
     * sesión, y hace falta para emitir y revocar sus enlaces.
     */
    /**
     * {@code preview} sólo viene cuando quien mira llega por la organización: es el dueño o el
     * encargado en "Ver como el comprador". Para el comprador es nulo, y el enlace público no
     * usa esta respuesta: los avisos de lo que falta cargar nunca le llegan a un comprador.
     */
    public record MyPropertyResponse(UUID unitId, PublicUnitView property, OwnerPreview preview) {
    }

    /** La obra de la casa, para armar los enlaces a donde se carga lo que falta. */
    public record OwnerPreview(UUID developmentId) {
    }
}