import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { loginTree } from './auth.guard';
import { AuthService, rejectsSession } from './auth.service';

/** Endpoints que no llevan Bearer: son los que emiten o revocan tokens. */
const PUBLIC_ENDPOINTS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'];

/**
 * La vista pública se autentica con el token del enlace, no con la sesión.
 *
 * <p>Adjuntarle el Bearer no rompería nada —el backend lo ignora— pero si la sesión
 * venció, el interceptor intentaría refrescarla y, al fallar, cerraría la sesión del
 * usuario por haber abierto una página pública. Una página que cualquiera puede ver no
 * debe tener efectos sobre la sesión de nadie.
 */
const SESSION_FREE_PREFIX = '/api/public/';

/**
 * El token se adjunta sólo a nuestra API.
 *
 * <p>Las subidas de archivos van por PUT directo al almacenamiento con URL firmada, a
 * otro origen. Mandarles el Bearer sería filtrar el token a un dominio ajeno y, además,
 * romper la firma: S3 autentica con ese mismo header Authorization, y el
 * almacenamiento responde 403 sin explicar por qué.
 */
function targetsOwnApi(url: string): boolean {
  return url.startsWith('/api/') || url.startsWith('/actuator/');
}

function withToken(request: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!targetsOwnApi(request.url) || request.url.startsWith(SESSION_FREE_PREFIX)) {
    return next(request);
  }

  if (PUBLIC_ENDPOINTS.some(endpoint => request.url.startsWith(endpoint))) {
    return next(request);
  }

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.accessToken();
  const authorized = token ? withToken(request, token) : request;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      // 401: el token venció. Se refresca y se reintenta una sola vez.
      if (error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refreshAccessToken().pipe(
        switchMap(newToken => next(withToken(request, newToken))),
        catchError(refreshError => {
          // Si el servidor rechazó la sesión, no hay nada que hacer en esta pantalla: se borra
          // y se va al ingreso, que después vuelve acá. Sin conexión, la sesión se conserva y
          // la pantalla muestra el error de siempre.
          if (rejectsSession(refreshError)) {
            auth.clearSession();
            if (!router.url.startsWith('/login')) {
              void router.navigateByUrl(loginTree(router, router.url));
            }
          }
          return throwError(() => refreshError);
        })
      );
    })
  );
};