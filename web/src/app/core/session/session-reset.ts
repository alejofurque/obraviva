import { Injectable } from '@angular/core';

/**
 * Lo que hay que olvidar cuando termina una sesión o empieza otra en el mismo navegador.
 *
 * <p>Los stores de la raíz (el contexto, el menú del dueño, las casas del encargado) viven
 * mientras la página no se recarga: sin esto, quien entra después de "Salir" vería lo del
 * anterior. Cada store registra su reseteo al crearse; `AuthService` los corre al cerrar la
 * sesión y al entrar. No depende de nada, así cualquiera lo inyecta sin ciclos.
 */
@Injectable({ providedIn: 'root' })
export class SessionReset {
  private readonly hooks = new Set<() => void>();

  onReset(hook: () => void): void {
    this.hooks.add(hook);
  }

  reset(): void {
    this.hooks.forEach(hook => hook());
  }
}

/** Clave de `localStorage` propia de una persona: en un navegador compartido no se pisan. */
export function userStorageKey(base: string, userId: string): string {
  return `${base}.${userId}`;
}
