import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, BaseRouteReuseStrategy } from '@angular/router';

/**
 * Por defecto Angular reusa el componente de una ruta cuando sólo cambian sus parámetros.
 * Los espacios del desarrollo y de la casa leen su id una vez y arman su store con él: el
 * menú lateral, que salta de una casa a otra (o de un desarrollo a otro) sin pasar por otra
 * pantalla, dejaría el encabezado y las pestañas de la anterior. Las rutas marcadas con
 * `data: { reloadOnParams: true }` se crean de nuevo cuando cambian sus parámetros, y con
 * ellas sus pestañas.
 */
@Injectable()
export class ParamAwareReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(future: ActivatedRouteSnapshot, current: ActivatedRouteSnapshot): boolean {
    if (future.routeConfig !== current.routeConfig) {
      return false;
    }
    return !future.routeConfig?.data?.['reloadOnParams'] || sameParams(future.params, current.params);
  }
}

function sameParams(a: Record<string, string>, b: Record<string, string>): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  return [...keys].every(key => a[key] === b[key]);
}
