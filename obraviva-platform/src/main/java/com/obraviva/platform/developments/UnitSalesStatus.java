package com.obraviva.platform.developments;

/**
 * Estado comercial de una unidad, visto por su organización. Nunca sale hacia el
 * comprador ni hacia el enlace público.
 */
public enum UnitSalesStatus {
    /** Se puede mostrar a interesados. */
    AVAILABLE,
    /** Comprometida con alguien, todavía sin cerrar. */
    RESERVED,
    /** Tiene comprador. */
    SOLD
}
