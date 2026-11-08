import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { UnitOverview } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { errorMessageOf } from '../../shared/http/error-message';

/**
 * La casa abierta en su espacio de trabajo. Lo provee el marco de la casa, junto con el
 * del desarrollo: cada pestaña lee la misma ficha, avance y puesta en marcha.
 *
 * <p>Que exista es lo que distingue una casa de las obras comunes: Etapas, Historia,
 * Recorridos y Económico lo inyectan opcional, y sin él trabajan sobre lo común.
 */
@Injectable()
export class HouseStore {

  private readonly developments = inject(DevelopmentsService);

  private readonly overviewSignal = signal<UnitOverview | null>(null);
  private readonly errorSignal = signal<{ message: string; retryable: boolean } | null>(null);
  private readonly unitIdSignal = signal('');
  /** El desarrollo de la dirección: la casa tiene que ser de ese. */
  private developmentId: string | null = null;

  readonly overview = this.overviewSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly unitId = this.unitIdSignal.asReadonly();
  readonly unit = computed(() => this.overviewSignal()?.unit ?? null);

  load(unitId: string, developmentId: string | null = null): void {
    this.unitIdSignal.set(unitId);
    this.developmentId = developmentId;
    this.reload();
  }

  reload(): void {
    this.errorSignal.set(null);
    this.developments.unitOverview(this.unitIdSignal()).subscribe({
      next: overview => {
        // `/obras/A/casas/<casa de B>` mezclaría el encabezado de un desarrollo con la casa de
        // otro: se trata como una casa que no existe.
        if (this.developmentId && overview.developmentId !== this.developmentId) {
          this.errorSignal.set({ message: 'No encontramos esta casa en tu cuenta.', retryable: false });
          return;
        }
        this.overviewSignal.set(overview);
      },
      error: (error: unknown) => this.errorSignal.set(
        error instanceof HttpErrorResponse && error.status === 404
          ? { message: 'No encontramos esta casa en tu cuenta.', retryable: false }
          : { message: errorMessageOf(error, 'No se pudo cargar la casa.'), retryable: true }),
    });
  }
}
