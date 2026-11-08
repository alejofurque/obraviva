import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivityService } from '../../core/api/activity.service';
import { Development, UnseenCount } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { OwnerNavStore } from '../../core/session/owner-nav.store';

/**
 * La obra abierta en el espacio de trabajo. Lo provee el marco de la obra, así cada
 * pestaña lee el mismo detalle, incluido el rol de quien la mira.
 */
@Injectable()
export class WorkStore {

  private readonly developments = inject(DevelopmentsService);
  private readonly activity = inject(ActivityService);
  /** El menú lateral muestra el mismo contador: lo que se ve acá se apaga allá. */
  private readonly nav = inject(OwnerNavStore);

  private readonly developmentSignal = signal<Development | null>(null);
  private readonly errorSignal = signal<{ message: string; retryable: boolean } | null>(null);
  private developmentId = '';

  readonly development = this.developmentSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  /** El dueño administra; el encargado publica avances y capturas. La restricción real está en el servidor. */
  readonly isOwner = computed(() => this.developmentSignal()?.role === 'OWNER');

  /**
   * Un encargado de casas puntuales trabaja sólo en ellas: las obras comunes las carga un
   * encargado de todo el desarrollo o el dueño.
   */
  readonly canWorkOnCommon = computed(() => this.developmentSignal()?.restrictedToUnitIds == null);

  /** Obra única: la obra es lo que se entrega, sin unidades a la vista. */
  readonly isSingle = computed(() => this.developmentSignal()?.kind === 'SINGLE');

  private readonly unseenSignal = signal<UnseenCount | null>(null);
  /** Novedades sin ver, para el punto de la pestaña. Sólo el dueño; nulo mientras no se sabe. */
  readonly unseen = this.unseenSignal.asReadonly();

  load(developmentId: string): void {
    this.developmentId = developmentId;
    this.reload();
  }

  /** El contador es un aviso: si falla, la pestaña queda sin punto. */
  refreshUnseen(): void {
    if (!this.isOwner()) {
      this.unseenSignal.set(null);
      return;
    }
    this.activity.unseenCount(this.developmentId).subscribe({
      next: count => {
        this.unseenSignal.set(count);
        this.nav.setUnseen(this.developmentId, count);
      },
      error: () => this.unseenSignal.set(null),
    });
  }

  /** Al entrar a Novedades, lo mostrado queda visto. */
  clearUnseen(): void {
    this.unseenSignal.set({ count: 0, more: false });
    this.nav.setUnseen(this.developmentId, { count: 0, more: false });
  }

  reload(): void {
    this.errorSignal.set(null);
    this.developments.detail(this.developmentId).subscribe({
      next: development => {
        this.developmentSignal.set(development);
        this.refreshUnseen();
      },
      error: (error: unknown) => this.errorSignal.set(
        error instanceof HttpErrorResponse && error.status === 404
          ? { message: 'No encontramos esta obra en tu cuenta.', retryable: false }
          : { message: errorMessageOf(error, 'No se pudo cargar la obra.'), retryable: true }),
    });
  }

  basePath(): string[] {
    return ['/obras', this.developmentId];
  }
}
