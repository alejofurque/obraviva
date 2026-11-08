import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { SessionReset } from '../session/session-reset';
import { AuthService } from './auth.service';

const REFRESH_KEY = 'obraviva.refreshToken';
const USER = { id: 'u1', email: 'ana@obraviva.test', fullName: 'Ana Paz' };

describe('AuthService: la sesión', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let resets: number;

  beforeEach(() => {
    localStorage.setItem(REFRESH_KEY, 'refresh-viejo');
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    resets = 0;
    TestBed.inject(SessionReset).onReset(() => resets++);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem(REFRESH_KEY);
  });

  function refreshFails(status: number): void {
    http.expectOne('/api/auth/refresh').flush({ message: 'no' }, { status, statusText: 'x' });
  }

  it('recupera la sesión guardada al arrancar', async () => {
    const result = firstValueFrom(auth.restoreSession());
    http.expectOne('/api/auth/refresh').flush({ accessToken: 'a2', refreshToken: 'r2' });
    http.expectOne('/api/auth/me').flush(USER);

    expect(await result).toBe('restored');
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.currentUser()?.id).toBe('u1');
    expect(localStorage.getItem(REFRESH_KEY)).toBe('r2');
  });

  it('si el servidor rechaza la sesión (401), la borra y resetea los stores', async () => {
    const result = firstValueFrom(auth.restoreSession());
    refreshFails(401);

    expect(await result).toBe('expired');
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
    expect(resets).toBe(1);
    expect(auth.restoreProblem()).toBeNull();
  });

  it('sin conexión no borra la sesión guardada y avisa', async () => {
    const result = firstValueFrom(auth.restoreSession());
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('error'), { status: 0 });

    expect(await result).toBe('offline');
    expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-viejo');
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.hasStoredSession()).toBe(true);
    expect(auth.restoreProblem()).toContain('Tu sesión sigue guardada');
    expect(resets).toBe(0);
  });

  it('con un 5xx tampoco la borra', async () => {
    const result = firstValueFrom(auth.restoreSession());
    refreshFails(503);

    expect(await result).toBe('offline');
    expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-viejo');
  });

  it('sin sesión guardada no pide nada', async () => {
    localStorage.removeItem(REFRESH_KEY);
    expect(await firstValueFrom(auth.restoreSession())).toBe('none');
  });

  it('salir borra la sesión y resetea los stores', async () => {
    const done = firstValueFrom(auth.logout());
    http.expectOne('/api/auth/logout').flush(null);
    await done;

    expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
    expect(resets).toBe(1);
  });

  it('entrar con otra cuenta resetea lo que quedó de la anterior', async () => {
    const done = firstValueFrom(auth.login({ email: 'otra@obraviva.test', password: 'clave' }));
    http.expectOne('/api/auth/login').flush({ accessToken: 'a3', refreshToken: 'r3' });
    http.expectOne('/api/auth/me').flush({ ...USER, id: 'u2' });
    await done;

    expect(resets).toBe(1);
    expect(auth.currentUser()?.id).toBe('u2');
  });
});
