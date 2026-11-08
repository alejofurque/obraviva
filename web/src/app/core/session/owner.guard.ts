import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { UserContextService } from './user-context.service';

/**
 * Pantallas del dueño (venta, ficha, económico): quien no es dueño de esa obra vuelve al
 * Resumen de la casa o del desarrollo, en vez de ver formularios que no puede usar. El
 * servidor ya rechaza lo que mandaría; esto es para no mostrárselos.
 *
 * <p>Si la obra no está en el contexto (recién creada, o el contexto no cargó) deja pasar:
 * la pantalla y el servidor responden como siempre.
 */
export const ownerGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const context = inject(UserContextService);
  const router = inject(Router);
  const developmentId = route.paramMap.get('developmentId') ?? '';
  const unitId = route.paramMap.get('unitId');

  return context.ensureLoaded().pipe(
    map(() => {
      const development = context.developments().find(item => item.id === developmentId);
      if (!development || development.role === 'OWNER') {
        return true;
      }
      return router.createUrlTree(unitId ? ['/obras', developmentId, 'casas', unitId] : ['/obras', developmentId]);
    }),
    catchError(() => of(true)),
  );
};
