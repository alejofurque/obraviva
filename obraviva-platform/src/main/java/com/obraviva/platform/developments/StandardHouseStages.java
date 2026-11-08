package com.obraviva.platform.developments;

import java.util.List;

/**
 * Etapas estándar de una casa de construcción tradicional en Argentina, en orden de obra.
 *
 * <p>El orden y la descripción siguen las guías de obra habituales (trabajos preliminares,
 * fundaciones, estructura, mampostería, cubierta, instalaciones, revoques, aberturas,
 * pisos y terminaciones). Los pesos reflejan la incidencia típica de cada etapa en el
 * costo: la obra gruesa (fundaciones a cubierta) ronda la mitad del total, las
 * instalaciones entre un 12 y un 18%, y los revoques con contrapisos entre un 10 y un 14%.
 * Suman 100 para que se lean como porcentajes, aunque el cálculo del avance no lo exige.
 *
 * <p>Es un punto de partida: el dueño renombra, agrega o ajusta pesos casa por casa.
 */
public final class StandardHouseStages {

    private StandardHouseStages() {
    }

    public record StandardStage(String name, String description, int weight) {
    }

    public static final List<StandardStage> STAGES = List.of(
            new StandardStage("Trabajos preliminares",
                    "Limpieza del terreno, cerco de obra, obrador, replanteo y conexiones provisorias de agua y luz.", 3),
            new StandardStage("Movimiento de suelo y fundaciones",
                    "Excavación, bases o plateas, vigas de fundación y capa aisladora.", 10),
            new StandardStage("Estructura",
                    "Columnas, vigas y losas de hormigón armado.", 15),
            new StandardStage("Mampostería",
                    "Muros exteriores e interiores, dinteles y tabiques.", 12),
            new StandardStage("Cubierta y techo",
                    "Estructura del techo, aislaciones, cubierta y desagües pluviales.", 10),
            new StandardStage("Instalación sanitaria y de gas",
                    "Cañerías de agua fría y caliente, desagües cloacales y red de gas.", 8),
            new StandardStage("Instalación eléctrica",
                    "Cañerías, cajas, cableado y tablero.", 6),
            new StandardStage("Revoques, contrapisos y carpetas",
                    "Revoque grueso y fino, contrapisos y carpetas de nivelación.", 11),
            new StandardStage("Aberturas y carpinterías",
                    "Ventanas, puertas exteriores e interiores, y muebles de obra.", 8),
            new StandardStage("Pisos y revestimientos",
                    "Pisos, zócalos y revestimientos de cocina y baños.", 8),
            new StandardStage("Pintura y terminaciones",
                    "Pintura, artefactos, grifería y detalles finales.", 6),
            new StandardStage("Limpieza final y entrega",
                    "Limpieza de obra, pruebas de instalaciones y entrega de llaves.", 3));
}
