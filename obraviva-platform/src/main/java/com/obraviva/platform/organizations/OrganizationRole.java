package com.obraviva.platform.organizations;

/**
 * Rol dentro de una organización. Provisorio: la matriz de permisos
 * por recurso (usuario, desarrollo, unidad, acción) se implementa
 * en la tarea de autorización. Los compradores no tienen rol de
 * organización: se vinculan por unidad.
 */
public enum OrganizationRole {
    OWNER,
    MEMBER
}