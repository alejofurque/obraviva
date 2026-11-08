import { HttpErrorResponse } from '@angular/common/http';

/**
 * Mensaje de error para mostrar en pantalla.
 *
 * <p>Prioriza el motivo que explicó el backend: un texto genérico ocultaría la causa
 * real, que el servidor ya redactó para una persona. Sin conexión no hay mensaje del
 * backend, y el problema tampoco es la acción: se dice eso, que es lo accionable.
 */
export function errorMessageOf(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'No hay conexión con el servidor. Revisá tu conexión y volvé a intentar.';
    }
    const message = (error.error as { message?: unknown } | null)?.message;
    if (typeof message === 'string' && message.trim()) {
      return message;
    }
  }
  return fallback;
}
