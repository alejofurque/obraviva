import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { catchError, finalize, map, shareReplay, switchMap, tap } from 'rxjs/operators';
import { SessionReset } from '../session/session-reset';
import { CurrentUser, LoginRequest, LoginResponse } from './auth.models';

const REFRESH_TOKEN_KEY = 'obraviva.refreshToken';

/**
 * Cómo terminó el intento de recuperar la sesión guardada: recuperada, no había, el servidor
 * la rechazó (y se borró) o no se pudo hablar con el servidor (y se conserva).
 */
export type RestoreResult = 'restored' | 'none' | 'expired' | 'offline';

/**
 * El servidor rechazó el token: la sesión no sirve más. Cualquier otro error (sin conexión,
 * un 5xx) no dice nada de la sesión, y borrarla obligaría a entrar de nuevo por un corte.
 * Sin token guardado, el refresco falla con un Error común: tampoco hay sesión.
 */
export function rejectsSession(error: unknown): boolean {
  if (error instanceof HttpErrorResponse) {
    return error.status === 400 || error.status === 401 || error.status === 403;
  }
  return true;
}

@Injectable({ providedIn: 'root' })
export class AuthService {

  private readonly http = inject(HttpClient);
  private readonly sessionReset = inject(SessionReset);

  /** Token de acceso: solo en memoria. Se pierde al recargar y se recupera con el refresh. */
  private readonly accessTokenSignal = signal<string | null>(null);
  private readonly currentUserSignal = signal<CurrentUser | null>(null);
  private readonly restoreProblemSignal = signal<string | null>(null);

  readonly accessToken = this.accessTokenSignal.asReadonly();
  readonly currentUser = this.currentUserSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.accessTokenSignal() !== null);
  /** Hay sesión guardada pero no se pudo recuperar porque el servidor no respondió. */
  readonly restoreProblem = this.restoreProblemSignal.asReadonly();

  /** Refresco en curso compartido: evita disparar varios refrescos simultáneos. */
  private refreshInFlight: Observable<string> | null = null;

  /** Entrar empieza una sesión nueva: lo que quedó de otra persona se olvida antes. */
  login(credentials: LoginRequest): Observable<CurrentUser> {
    return this.http.post<LoginResponse>('/api/auth/login', credentials).pipe(
      tap(response => {
        this.sessionReset.reset();
        this.restoreProblemSignal.set(null);
        this.storeSession(response);
      }),
      switchMap(() => this.loadCurrentUser())
    );
  }

  logout(): Observable<unknown> {
    const refreshToken = this.readRefreshToken();
    const request = refreshToken
      ? this.http.post('/api/auth/logout', { refreshToken }).pipe(catchError(() => of(null)))
      : of(null);

    return request.pipe(tap(() => this.clearSession()));
  }

  /**
   * Cambio de la propia contraseña. El backend cierra las demás sesiones y devuelve
   * tokens nuevos, que reemplazan a los actuales para seguir adentro.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http
      .post<LoginResponse>('/api/auth/password', { currentPassword, newPassword })
      .pipe(
        tap(response => this.storeSession(response)),
        map(() => undefined),
      );
  }

  /** Si hay una sesión guardada en el navegador, aunque todavía no se haya recuperado. */
  hasStoredSession(): boolean {
    return this.readRefreshToken() !== null;
  }

  /**
   * Recupera la sesión guardada en silencio: al arrancar la app y cuando una guarda la
   * necesita. Sólo la borra si el servidor la rechaza; sin conexión o con un 5xx la deja
   * guardada para reintentar, y avisa con `restoreProblem`.
   */
  restoreSession(): Observable<RestoreResult> {
    if (!this.readRefreshToken()) {
      return of('none');
    }
    return this.refreshAccessToken().pipe(
      switchMap(() => this.loadCurrentUser()),
      map((): RestoreResult => {
        this.restoreProblemSignal.set(null);
        return 'restored';
      }),
      catchError((error: unknown) => {
        if (rejectsSession(error)) {
          this.clearSession();
          return of<RestoreResult>('expired');
        }
        // Sin el usuario no se puede armar nada: el token de acceso tampoco se usa.
        this.accessTokenSignal.set(null);
        this.restoreProblemSignal.set('No hay conexión con el servidor. Tu sesión sigue guardada: reintentá en un momento.');
        return of<RestoreResult>('offline');
      })
    );
  }

  refreshAccessToken(): Observable<string> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }

    const refreshToken = this.readRefreshToken();
    if (!refreshToken) {
      return throwError(() => new Error('No hay token de refresco'));
    }

    this.refreshInFlight = this.http
      .post<LoginResponse>('/api/auth/refresh', { refreshToken })
      .pipe(
        tap(response => this.storeSession(response)),
        map(response => response.accessToken),
        finalize(() => (this.refreshInFlight = null)),
        shareReplay(1)
      );

    return this.refreshInFlight;
  }

  /** Termina la sesión en este navegador: tokens, usuario y lo que guardaban los stores. */
  clearSession(): void {
    this.accessTokenSignal.set(null);
    this.currentUserSignal.set(null);
    this.restoreProblemSignal.set(null);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    this.sessionReset.reset();
  }

  private loadCurrentUser(): Observable<CurrentUser> {
    return this.http
      .get<CurrentUser>('/api/auth/me')
      .pipe(tap(user => this.currentUserSignal.set(user)));
  }

  private storeSession(response: LoginResponse): void {
    this.accessTokenSignal.set(response.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken);
  }

  private readRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }
}
