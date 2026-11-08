import { ApplicationConfig, LOCALE_ID, provideAppInitializer, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection, inject } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS, MatFormFieldDefaultOptions } from '@angular/material/form-field';
import { RouteReuseStrategy, provideRouter, withRouterConfig } from '@angular/router';
import { ParamAwareReuseStrategy } from './core/routing/param-reuse.strategy';
import { provideAppIcons } from './shared/ui/icons';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { BackNavigation } from './core/routing/back-navigation';
import { routes } from './app.routes';

// Sin esto, las fechas salen en inglés: Angular sólo trae el locale en-US en el
// paquete base. El registro va antes de la configuración porque tiene que estar
// hecho cuando el primer DatePipe se evalúe.
registerLocaleData(localeEsAr);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    // Las pantallas de una casa leen su unitId aunque cuelgue de la ruta padre.
    // 'computed' (opción pública del router): cuando el aviso de cambios sin guardar cancela un
    // atrás del navegador, el router vuelve a la entrada en la que estaba. Con 'replace', la
    // reescribe con la dirección actual y el historial queda con dos entradas iguales.
    provideRouter(routes, withRouterConfig({ paramsInheritanceStrategy: 'always', canceledNavigationResolution: 'computed' })),
    { provide: RouteReuseStrategy, useClass: ParamAwareReuseStrategy },
    provideHttpClient(withInterceptors([authInterceptor])),
    // Español de Argentina: afecta fechas, números y el separador decimal en toda la
    // aplicación, incluidas las cifras del panel del desarrollista.
    { provide: LOCALE_ID, useValue: 'es-AR' },
    provideAppIcons(),
    // Todos los campos con contorno: se leen mejor sobre fondo claro y delimitan el
    // área de toque en el teléfono.
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: { appearance: 'outline' } satisfies MatFormFieldDefaultOptions,
    },
    // Recupera la sesión antes de mostrar la app: si hay refresh válido,
    // el usuario no vuelve a ver el login al recargar.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).restoreSession())),
    // La regla de "Volver" cuenta las navegaciones desde la primera: se crea antes de que arranque.
    provideAppInitializer(() => void inject(BackNavigation)),
  ],
};