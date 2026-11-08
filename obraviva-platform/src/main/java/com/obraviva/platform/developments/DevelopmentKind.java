package com.obraviva.platform.developments;

/**
 * Tipo de desarrollo.
 *
 * <p>{@link #SINGLE}: obra única, una casa o un galpón. Tiene exactamente una unidad, que
 * se crea con la obra, y la interfaz no la muestra como algo aparte.
 *
 * <p>{@link #MULTI_UNIT}: edificio. Varias unidades comparten la obra, cada una con sus
 * compradores y sus recorridos, más los de áreas comunes.
 *
 * <p>Un barrio de casas son varias obras únicas: cada casa conserva su avance y su gasto.
 */
public enum DevelopmentKind {
    SINGLE,
    MULTI_UNIT
}
