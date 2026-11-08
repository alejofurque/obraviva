import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

const REFRESH_KEY = 'obraviva.refreshToken';

describe('authInterceptor: cuando vence el token', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.setItem(REFRESH_KEY, 'refresh');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/obras/d1/casas/u1/historia');
    navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem(REFRESH_KEY);
  });

  function requestThatExpires(): { error: unknown } {
    const outcome: { error: unknown } = { error: null };
    client.get('/api/developments').subscribe({ error: error => (outcome.error = error) });
    http.expectOne('/api/developments').flush(null, { status: 401, statusText: 'Unauthorized' });
    return outcome;
  }

  it('refresca y reintenta una vez, sin que la pantalla se entere', () => {
    let body: unknown = null;
    client.get('/api/developments').subscribe(value => (body = value));
    http.expectOne('/api/developments').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/refresh').flush({ accessToken: 'nuevo', refreshToken: 'r2' });
    const retry = http.expectOne('/api/developments');

    expect(retry.request.headers.get('Authorization')).toBe('Bearer nuevo');
    retry.flush([]);
    expect(body).toEqual([]);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('si el servidor rechaza el refresco, borra la sesión y va al ingreso recordando la pantalla', () => {
    requestThatExpires();
    http.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.hasStoredSession()).toBe(false);
    expect(navigate).toHaveBeenCalledTimes(1);
    const target = TestBed.inject(Router).serializeUrl(navigate.mock.calls[0][0] as UrlTree);
    expect(target).toBe('/login?volver=%2Fobras%2Fd1%2Fcasas%2Fu1%2Fhistoria');
  });

  it('sin conexión durante el refresco, la sesión se conserva y la pantalla ve el error', () => {
    const outcome = requestThatExpires();
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('error'), { status: 0 });

    expect(auth.hasStoredSession()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect((outcome.error as { status: number }).status).toBe(0);
  });

  it('la vista pública no toca la sesión aunque falle', () => {
    client.get('/api/public/share/abc').subscribe({ error: () => undefined });
    http.expectOne('/api/public/share/abc').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.hasStoredSession()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});
