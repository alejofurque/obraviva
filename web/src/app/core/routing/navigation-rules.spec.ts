import { Location, PlatformLocation } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable, filter, firstValueFrom, isObservable, of } from 'rxjs';
import { buyerSections } from '../../features/property/property-layout.component';
import { fieldActivityLinks } from '../../features/works/activity-derivations';
import { HouseStore } from '../../features/works/house.store';
import { hasOwnBar } from '../../shell/shell.component';
import { aUnit, anActivityItem } from '../../testing/fixtures';
import { supervisorGuard } from '../session/supervisor.guard';
import { UserContextService } from '../session/user-context.service';
import { BackLinkDirective, BackNavigation } from './back-navigation';

@Component({
  imports: [BackLinkDirective],
  template: `<a [appBack]="['/obras', 'd1', 'casas', 'u1', 'historia']">Volver</a>`,
})
class BackHostComponent {}

@Component({ template: '' })
class ScreenComponent {}

const SCREENS = [{ path: 'a', component: ScreenComponent }, { path: 'b', component: ScreenComponent }, { path: 'c', component: ScreenComponent }];

describe('Regla de "Volver"', () => {
  let location: Location;
  let router: Router;

  /**
   * La app arranca en una entrada del historial: como en `app.config.ts`, el servicio se crea
   * antes de la primera navegación, que entra con `replaceUrl` (así la hace el router). En
   * TestBed el historial es el simulado de Angular (`PlatformLocation`); `state` es lo que la
   * entrada ya tenía guardado, como después de un F5.
   */
  async function start(url: string, state: unknown = null): Promise<BackNavigation> {
    TestBed.configureTestingModule({ imports: [BackHostComponent], providers: [provideRouter(SCREENS)] });
    TestBed.inject(PlatformLocation).replaceState(state, '', url);
    location = TestBed.inject(Location);
    router = TestBed.inject(Router);
    const navigation = TestBed.inject(BackNavigation);
    // Como al arrancar la app: escucha el atrás del navegador y navega a la entrada actual.
    const ended = firstValueFrom(router.events.pipe(filter(event => event instanceof NavigationEnd)));
    router.initialNavigation();
    await ended;
    return navigation;
  }

  /** Recargar: la entrada del historial queda con su estado y la app empieza de cero. */
  async function reload(url: string): Promise<BackNavigation> {
    const state = location.getState();
    TestBed.resetTestingModule();
    return start(url, state);
  }

  it('entrada directa: no hay pantalla de la app antes, así que va al lugar fijo', async () => {
    const navigation = await start('/a');
    const back = vi.spyOn(location, 'back').mockImplementation(() => undefined);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    navigation.back('/hoy');

    expect(navigation.canGoBack()).toBe(false);
    expect(back).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/hoy');
  });

  it('si se llegó desde otra pantalla de la app, vuelve ahí', async () => {
    const navigation = await start('/a');
    await router.navigateByUrl('/b');
    const back = vi.spyOn(location, 'back').mockImplementation(() => undefined);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    navigation.back('/hoy');

    expect(back).toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('un filtro con replaceUrl no cuenta como pantalla', async () => {
    const navigation = await start('/a');
    await router.navigate(['/a'], { queryParams: { estado: 'SOLD' }, replaceUrl: true });

    expect(navigation.canGoBack()).toBe(false);
  });

  it('F5 en una pantalla a la que se llegó desde otra: sigue pudiendo volver', async () => {
    await start('/a');
    await router.navigateByUrl('/b');

    const navigation = await reload('/b');
    expect(navigation.canGoBack()).toBe(true);
  });

  it('F5 en la pantalla de entrada: sigue yendo al lugar fijo', async () => {
    await start('/a');

    const navigation = await reload('/a');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('con el atrás del navegador toma la cuenta de la entrada a la que vuelve', async () => {
    const navigation = await start('/a');
    await router.navigateByUrl('/b');
    await router.navigateByUrl('/c');
    const ended = firstValueFrom(router.events.pipe(filter(event => event instanceof NavigationEnd)));

    location.back();
    await ended;

    expect(location.path()).toBe('/b');
    expect(navigation.canGoBack()).toBe(true);
    const again = firstValueFrom(router.events.pipe(filter(event => event instanceof NavigationEnd)));
    location.back();
    await again;
    expect(location.path()).toBe('/a');
    expect(navigation.canGoBack()).toBe(false);
  });

  it('el enlace lleva el lugar fijo en el href (otra pestaña) y al tocarlo sigue la regla', async () => {
    const navigation = await start('/a');
    const fixture = TestBed.createComponent(BackHostComponent);
    fixture.detectChanges();
    const link = (fixture.nativeElement as HTMLElement).querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/obras/d1/casas/u1/historia');

    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    link.click();
    expect(router.serializeUrl(navigate.mock.calls[0][0] as UrlTree)).toBe('/obras/d1/casas/u1/historia');

    vi.spyOn(navigation, 'canGoBack').mockReturnValue(true);
    const back = vi.spyOn(location, 'back').mockImplementation(() => undefined);
    link.click();
    expect(back).toHaveBeenCalled();
  });
});

describe('Navegación por rol', () => {
  it('desde el campo, "Ver en la casa" va a La casa de Hoy y "Corregir" abre Publicar avance en modo corrección', () => {
    const entry = anActivityItem({ kind: 'ENTRY', unitId: 'u1' });
    const links = fieldActivityLinks(entry, 'd1');
    expect(links.view).toEqual(['/hoy', 'u1', 'casa']);
    expect(links.correct).toEqual({ link: ['/hoy', 'u1', 'avance', entry.entry!.entryId], query: null });

    expect(fieldActivityLinks(anActivityItem({ kind: 'PHOTOS', unitId: 'u1' }), 'd1').correct).toBeNull();
  });

  it('en el enlace público la primera pestaña es "La casa", sin repetir el nombre', () => {
    expect(buyerSections(true).map(section => section.label)).toEqual(['La casa', 'Recorrer en 3D', 'Historia', 'Planos y barrio']);
    expect(buyerSections(false).map(section => section.label)).toEqual(['Mi casa', 'Recorrer en 3D', 'Historia', 'Documentos']);
  });

  it('una sola barra arriba: el campo y la vista de una propiedad dibujan la suya y el shell no', () => {
    expect(hasOwnBar('/hoy')).toBe(true);
    expect(hasOwnBar('/mi-propiedad/u1')).toBe(true);
    expect(hasOwnBar('/mi-propiedad/u1/historia?obra=d1')).toBe(true);
    expect(hasOwnBar('/mis-propiedades')).toBe(false);
    expect(hasOwnBar('/desarrollos')).toBe(false);
    expect(hasOwnBar('/cuenta')).toBe(false);
  });

  async function runSupervisor(supervises: boolean): Promise<boolean | string> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: UserContextService, useValue: { ensureLoaded: () => of(undefined), supervises: () => supervises, homeUrl: () => ['/mi-propiedad', 'u1'] } },
      ],
    });
    const result = TestBed.runInInjectionContext(() => supervisorGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)) as Observable<boolean | UrlTree>;
    const value = isObservable(result) ? await firstValueFrom(result) : result;
    return value instanceof UrlTree ? TestBed.inject(Router).serializeUrl(value) : value;
  }

  it('/hoy es del encargado: a quien no supervisa nada lo lleva a su inicio', async () => {
    expect(await runSupervisor(true)).toBe(true);
    expect(await runSupervisor(false)).toBe('/mi-propiedad/u1');
  });

  it('una casa de otro desarrollo en la dirección se trata como una casa que no existe', () => {
    TestBed.configureTestingModule({ providers: [HouseStore, provideHttpClient(), provideHttpClientTesting()] });
    const house = TestBed.inject(HouseStore);
    const http = TestBed.inject(HttpTestingController);

    house.load('u9', 'd1');
    http.expectOne('/api/units/u9/overview').flush({ unitId: 'u9', developmentId: 'd2', unit: aUnit({ id: 'u9', developmentId: 'd2' }) });

    expect(house.unit()).toBeNull();
    expect(house.error()).toEqual({ message: 'No encontramos esta casa en tu cuenta.', retryable: false });
  });
});
