import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, of, throwError } from 'rxjs';
import { Development } from '../api/api.models';
import { aDevelopment } from '../../testing/fixtures';
import { ownerGuard } from './owner.guard';
import { UserContextService } from './user-context.service';

describe('Guarda de las pantallas del dueño', () => {
  function run(developments: Development[], params: Record<string, string>, load: Observable<void> = of(undefined)) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: UserContextService, useValue: { ensureLoaded: () => load, developments: () => developments } },
      ],
    });
    const route = { paramMap: convertToParamMap(params) } as ActivatedRouteSnapshot;
    const result = TestBed.runInInjectionContext(() => ownerGuard(route, {} as never)) as Observable<boolean | UrlTree>;
    return firstValueFrom(result);
  }

  const url = (result: boolean | UrlTree) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  it('el dueño pasa', async () => {
    expect(await run([aDevelopment({ id: 'd1', role: 'OWNER' })], { developmentId: 'd1', unitId: 'u1' })).toBe(true);
  });

  it('el encargado vuelve al Resumen de la casa, o del desarrollo si no hay casa', async () => {
    const supervised = [aDevelopment({ id: 'd1', role: 'MEMBER' })];
    expect(url(await run(supervised, { developmentId: 'd1', unitId: 'u1' }))).toBe('/obras/d1/casas/u1');
    expect(url(await run(supervised, { developmentId: 'd1' }))).toBe('/obras/d1');
  });

  it('sin la obra en el contexto, o si no cargó, deja pasar: responde la pantalla', async () => {
    expect(await run([], { developmentId: 'd1' })).toBe(true);
    expect(await run([], { developmentId: 'd1' }, throwError(() => new Error('red')))).toBe(true);
  });
});
