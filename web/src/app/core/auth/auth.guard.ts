import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { Observable, catchError, map, of, switchMap } from 'rxjs';
import { UserContextService } from '../session/user-context.service';
import { AuthService } from './auth.service';

/** Parámetro del ingreso con la dirección a la que iba la persona. */
export const RETURN_PARAM = 'volver';

/**
 * La dirección a la que volver después de entrar, si es de esta app: empieza con una sola
 * barra (no `//otro-sitio`) y no es el ingreso mismo. Si no, nula y se va al inicio.
 */
export function safeReturnUrl(value: string | null | undefined): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null;
  }
  return value === '/login' || value.startsWith('/login?') || value.startsWith('/login/') ? null : value;
}

/** El ingreso, recordando adónde se iba. */
export function loginTree(router: Router, returnUrl: string | null): UrlTree {
  const back = safeReturnUrl(returnUrl);
  return router.createUrlTree(['/login'], back ? { queryParams: { [RETURN_PARAM]: back } } : {});
}

/**
 * Pantallas con sesión. Si la sesión está guardada pero no se recuperó al arrancar (el
 * servidor no respondía), se reintenta acá antes de mandar al ingreso.
 */
export const authGuard: CanActivateFn = (_route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  if (!auth.hasStoredSession()) {
    return loginTree(router, state.url);
  }
  return auth.restoreSession().pipe(map(result => result === 'restored' ? true : loginTree(router, state.url)));
};

/** Adónde entra la persona: la dirección pedida, si la hay, o su inicio. */
export function entryTree(router: Router, context: UserContextService, returnUrl: string | null): Observable<UrlTree> {
  const back = safeReturnUrl(returnUrl);
  if (back) {
    return of(router.parseUrl(back));
  }
  return context.ensureLoaded().pipe(
    map(() => router.createUrlTree(context.homeUrl())),
    // Si no se pudo cargar el contexto, el inicio muestra el error con reintento.
    catchError(() => of(router.createUrlTree(['/inicio']))),
  );
}

/** El ingreso con la sesión abierta no tiene sentido: se va a donde iba o a su inicio. */
export const guestGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const context = inject(UserContextService);
  const returnUrl = route.queryParamMap.get(RETURN_PARAM);

  if (auth.isAuthenticated()) {
    return entryTree(router, context, returnUrl);
  }
  if (!auth.hasStoredSession()) {
    return true;
  }
  return auth.restoreSession().pipe(
    switchMap(result => result === 'restored' ? entryTree(router, context, returnUrl) : of(true)),
  );
};
