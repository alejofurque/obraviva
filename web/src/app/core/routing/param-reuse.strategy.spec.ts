import { ActivatedRouteSnapshot, Route } from '@angular/router';
import { ParamAwareReuseStrategy } from './param-reuse.strategy';

describe('Reuso de rutas por parámetros', () => {
  const strategy = new ParamAwareReuseStrategy();
  const snapshot = (routeConfig: Route, params: Record<string, string>) =>
    ({ routeConfig, params }) as unknown as ActivatedRouteSnapshot;

  const house: Route = { path: 'obras/:developmentId/casas/:unitId', data: { reloadOnParams: true } };
  const plain: Route = { path: 'historia' };

  it('el espacio de una casa se crea de nuevo al pasar a otra casa, y se reusa en la misma', () => {
    expect(strategy.shouldReuseRoute(snapshot(house, { unitId: 'u2' }), snapshot(house, { unitId: 'u1' }))).toBe(false);
    expect(strategy.shouldReuseRoute(snapshot(house, { unitId: 'u1' }), snapshot(house, { unitId: 'u1' }))).toBe(true);
  });

  it('una ruta sin la marca se comporta como siempre', () => {
    expect(strategy.shouldReuseRoute(snapshot(plain, { id: 'b' }), snapshot(plain, { id: 'a' }))).toBe(true);
    expect(strategy.shouldReuseRoute(snapshot(plain, {}), snapshot(house, {}))).toBe(false);
  });
});
