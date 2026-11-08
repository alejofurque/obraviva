import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable, of } from 'rxjs';
import { UserContextService } from '../session/user-context.service';
import { RestoreResult, AuthService } from './auth.service';
import { authGuard, guestGuard, safeReturnUrl } from './auth.guard';

type GuardResult = boolean | UrlTree | Observable<boolean | UrlTree>;

describe('Guardas de sesión', () => {
  let authenticated: boolean;
  let stored: boolean;
  let restore: RestoreResult;

  beforeEach(() => {
    authenticated = false;
    stored = false;
    restore = 'offline';
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => authenticated,
            hasStoredSession: () => stored,
            restoreSession: () => of(restore),
          },
        },
        { provide: UserContextService, useValue: { ensureLoaded: () => of(undefined), homeUrl: () => ['/hoy'] } },
      ],
    });
  });

  async function resolve(result: GuardResult): Promise<string | boolean> {
    const value = isObservable(result) ? await firstValueFrom(result) : result;
    return value instanceof UrlTree ? TestBed.inject(Router).serializeUrl(value) : value;
  }

  function runAuth(url: string): Promise<string | boolean> {
    return resolve(TestBed.runInInjectionContext(
      () => authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)) as GuardResult);
  }

  function runGuest(query: Record<string, string> = {}): Promise<string | boolean> {
    const route = { queryParamMap: convertToParamMap(query) } as ActivatedRouteSnapshot;
    return resolve(TestBed.runInInjectionContext(() => guestGuard(route, {} as RouterStateSnapshot)) as GuardResult);
  }

  it('sin sesión, al ingreso recordando la dirección', async () => {
    expect(await runAuth('/obras/d1/casas')).toBe('/login?volver=%2Fobras%2Fd1%2Fcasas');
  });

  it('con la sesión guardada y el servidor de vuelta, la recupera y deja pasar', async () => {
    stored = true;
    restore = 'restored';
    expect(await runAuth('/obras/d1')).toBe(true);
  });

  it('con la sesión guardada y sin servidor, al ingreso (que avisa y deja reintentar)', async () => {
    stored = true;
    expect(await runAuth('/obras/d1')).toBe('/login?volver=%2Fobras%2Fd1');
  });

  it('el ingreso con la sesión abierta lleva al inicio', async () => {
    authenticated = true;
    expect(await runGuest()).toBe('/hoy');
  });

  it('el ingreso con la sesión abierta y una dirección pedida lleva a esa dirección', async () => {
    authenticated = true;
    expect(await runGuest({ volver: '/hoy/u1/casa' })).toBe('/hoy/u1/casa');
  });

  it('el ingreso sin sesión se muestra', async () => {
    expect(await runGuest()).toBe(true);
  });

  it('sólo se vuelve a direcciones de la app', () => {
    expect(safeReturnUrl('/obras/d1?x=1')).toBe('/obras/d1?x=1');
    expect(safeReturnUrl('//otro-sitio.com')).toBeNull();
    expect(safeReturnUrl('/\\otro-sitio.com')).toBeNull();
    expect(safeReturnUrl('https://otro-sitio.com')).toBeNull();
    expect(safeReturnUrl('/login?volver=/x')).toBeNull();
    expect(safeReturnUrl(null)).toBeNull();
  });
});
