import { Development, Unit, UnseenCount } from '../../core/api/api.models';

/** Una sección de un desarrollo o una pestaña de una casa, relativa a su base. */
export interface WorkSection {
  label: string;
  path: string;
  exact: boolean;
  /** Lo que queda sin ver, junto al nombre: "3", "99+". */
  badge?: string | null;
}

/** "3", "99+"; nulo si no hay nada sin ver o todavía no se sabe. */
export function unseenBadge(unseen: UnseenCount | null | undefined): string | null {
  return unseen && unseen.count > 0 ? `${unseen.count}${unseen.more ? '+' : ''}` : null;
}

/**
 * Las secciones de un desarrollo, en el orden del trabajo real: cómo va, lo nuevo, sus casas
 * (donde está el día a día), lo que es de todos y lo del dueño, el gasto y el equipo. Las
 * mismas en el menú lateral del dueño y en las pestañas que ve el encargado. Los datos del
 * desarrollo no son una sección: se editan desde el encabezado ("Editar datos").
 *
 * <p>Ocultar una sección es ayuda de interfaz: la restricción real está en el servidor.
 */
export function developmentSections(development: Development, unseen: UnseenCount | null): WorkSection[] {
  const owner = development.role === 'OWNER';
  const sections: WorkSection[] = [{ label: 'Resumen', path: '', exact: true }];
  if (owner) {
    sections.push({ label: 'Novedades', path: 'novedades', exact: false, badge: unseenBadge(unseen) });
  }
  sections.push({ label: 'Casas', path: 'casas', exact: false });
  // Un encargado de casas puntuales no carga en las obras comunes: no se le ofrecen.
  if (development.restrictedToUnitIds == null) {
    sections.push({ label: 'Obras comunes', path: 'comunes', exact: false });
  }
  if (owner) {
    sections.push(
      { label: 'Económico', path: 'economico', exact: false },
      { label: 'Equipo', path: 'equipo', exact: false },
    );
  }
  return sections;
}

/** Lo del día a día primero; Novedades de la casa para todos; lo comercial y el gasto, sólo el dueño. */
export function houseTabs(owner: boolean): WorkSection[] {
  const tabs: WorkSection[] = [
    { label: 'Resumen', path: '', exact: true },
    { label: 'Etapas', path: 'etapas', exact: false },
    { label: 'Historia', path: 'historia', exact: false },
    { label: 'Lugares y 3D', path: 'recorridos', exact: false },
    { label: 'Novedades', path: 'novedades', exact: false },
  ];
  if (owner) {
    tabs.push(
      { label: 'Ficha y galería', path: 'ficha', exact: false },
      { label: 'Venta', path: 'venta', exact: false },
      { label: 'Económico', path: 'economico', exact: false },
    );
  }
  return tabs;
}

/** Cuántas casas se listan en el menú lateral antes de "Ver todas". */
export const MENU_HOUSES = 12;

/** Las casas por su código, con los números en orden natural: "Casa 2" antes que "Casa 10". */
export function sortHouses<T extends Pick<Unit, 'code'>>(units: T[]): T[] {
  return [...units].sort((a, b) => a.code.localeCompare(b.code, 'es', { numeric: true }));
}
