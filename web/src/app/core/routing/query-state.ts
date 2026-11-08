import { ActivatedRoute, Params, Router } from '@angular/router';

/**
 * Cambia parámetros de la dirección actual sin sumar una entrada al historial: el atrás del
 * navegador sigue yendo a la pantalla anterior, no al filtro anterior. Un valor nulo saca el
 * parámetro. Lo usan los filtros y las selecciones que tienen que sobrevivir a volver con el
 * atrás o a recargar.
 */
export function replaceQuery(router: Router, route: ActivatedRoute, params: Params): void {
  void router.navigate([], { relativeTo: route, queryParams: params, queryParamsHandling: 'merge', replaceUrl: true });
}

/**
 * Saca parámetros de un solo uso (`?etapa=`, `?corregir=`, `?nueva=1`, `?nuevo=1`) una vez
 * usados: recargar o volver con el atrás no repite la acción.
 */
export function clearQuery(router: Router, route: ActivatedRoute, ...names: string[]): void {
  replaceQuery(router, route, Object.fromEntries(names.map(name => [name, null])));
}
