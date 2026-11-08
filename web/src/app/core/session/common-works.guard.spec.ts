import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Route, Router, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, of } from 'rxjs';
import { routes } from '../../app.routes';
import { Development } from '../api/api.models';
import { aDevelopment } from '../../testing/fixtures';
import { commonWorksGuard } from './common-works.guard';
import { ownerGuard } from './owner.guard';
import { UserContextService } from './user-context.service';

describe('Guardas de las obras comunes y de las pantallas del dueño', () => {
  async function run(developments: Development[]): Promise<boolean | string> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: UserContextService, useValue: { ensureLoaded: () => of(undefined), developments: () => developments } },
      ],
    });
    const route = { paramMap: convertToParamMap({ developmentId: 'd1' }) } as ActivatedRouteSnapshot;
    const result = await firstValueFrom(TestBed.runInInjectionContext(() => commonWorksGuard(route, {} as never)) as Observable<boolean | UrlTree>);
    return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : result;
  }

  it('el encargado de casas puntuales no entra a las obras comunes: vuelve al Resumen', async () => {
    expect(await run([aDevelopment({ id: 'd1', role: 'MEMBER', restrictedToUnitIds: ['u1', 'u2'] })])).toBe('/obras/d1');
  });

  it('el encargado de todo el desarrollo y el dueño entran', async () => {
    expect(await run([aDevelopment({ id: 'd1', role: 'MEMBER', restrictedToUnitIds: null })])).toBe(true);
    expect(await run([aDevelopment({ id: 'd1', role: 'OWNER', restrictedToUnitIds: null })])).toBe(true);
  });

  it('Novedades, datos y equipo del desarrollo llevan la guarda del dueño; las Novedades de una casa, no', () => {
    const development = routes.find(route => route.path === 'obras/:developmentId' || route.children?.some(child => child.path === 'obras/:developmentId'));
    const shell = routes.find(route => route.children?.some(child => child.path === 'obras/:developmentId'))!;
    const work = shell.children!.find(child => child.path === 'obras/:developmentId')!;
    const house = shell.children!.find(child => child.path === 'obras/:developmentId/casas/:unitId')!;
    const guarded = (parent: Route, path: string) => parent.children!.find(child => child.path === path)!.canActivate?.includes(ownerGuard) ?? false;

    expect(development).toBeDefined();
    expect(['novedades', 'datos', 'equipo', 'economico'].map(path => guarded(work, path))).toEqual([true, true, true, true]);
    expect(work.children!.find(child => child.path === 'comunes')!.canActivate).toContain(commonWorksGuard);
    expect(guarded(house, 'novedades')).toBe(false);
  });

  it('el trabajo de campo ya no tiene la ruta suelta de novedades', () => {
    const shell = routes.find(route => route.children?.some(child => child.path === 'hoy'))!;
    const field = shell.children!.find(child => child.path === 'hoy')!;
    expect(field.children!.map(child => child.path)).not.toContain(':unitId/novedades');
  });
});
