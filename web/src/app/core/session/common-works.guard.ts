import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { UserContextService } from './user-context.service';

/**
 * Las obras comunes no son de un encargado de casas puntuales: las carga uno de todo el
 * desarrollo o el dueño. Quien tiene casas puntuales vuelve al Resumen del desarrollo, en vez
 * de ver pantallas en las que el servidor le rechazaría todo. Si la obra no está en el
 * contexto o el contexto no carga, deja pasar: responde la pantalla.
 */
export const commonWorksGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const context = inject(UserContextService);
  const router = inject(Router);
  const developmentId = route.paramMap.get('developmentId') ?? '';

  return context.ensureLoaded().pipe(
    map(() => {
      const development = context.developments().find(item => item.id === developmentId);
      return development && development.restrictedToUnitIds != null ? router.createUrlTree(['/obras', developmentId]) : true;
    }),
    catchError(() => of(true)),
  );
};
