import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { UserContextService } from './user-context.service';

/**
 * El trabajo de campo (`/hoy`) es del encargado. Un comprador o un dueño que no supervisa
 * ninguna obra vuelve a su inicio, en vez de ver el marco del encargado vacío. Si el contexto
 * no carga, deja pasar: la pantalla muestra su error.
 */
export const supervisorGuard: CanActivateFn = () => {
  const context = inject(UserContextService);
  const router = inject(Router);
  return context.ensureLoaded().pipe(
    map(() => context.supervises() ? true : router.createUrlTree(context.homeUrl())),
    catchError(() => of(true)),
  );
};
