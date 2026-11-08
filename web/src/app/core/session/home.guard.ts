import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { of, switchMap } from 'rxjs';
import { entryTree } from '../auth/auth.guard';
import { AuthService } from '../auth/auth.service';
import { UserContextService } from './user-context.service';

/**
 * Resuelve la entrada a la aplicación: sin sesión, al login; con sesión, a lo que la
 * persona tiene. Así un comprador ya no cae en un listado de obras vacío.
 */
export const homeRedirectGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const context = inject(UserContextService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return entryTree(router, context, null);
  }
  if (!auth.hasStoredSession()) {
    return router.createUrlTree(['/login']);
  }
  return auth.restoreSession().pipe(
    switchMap(result => result === 'restored' ? entryTree(router, context, null) : of(router.createUrlTree(['/login']))),
  );
};
