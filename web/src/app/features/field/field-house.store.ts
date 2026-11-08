import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, shareReplay, tap } from 'rxjs';
import { AssignedHouse } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionReset, userStorageKey } from '../../core/session/session-reset';
import { errorMessageOf } from '../../shared/http/error-message';

const SELECTED_KEY = 'obraviva.field.house';

/**
 * Las casas del encargado y la que eligió, compartidas por todo el trabajo de campo: Hoy, la
 * barra de abajo del teléfono y la de arriba en escritorio leen la misma. La elección queda
 * recordada en el navegador, por persona; si no hay, la primera casa. Entrar a una pantalla
 * de una casa (`/hoy/:unitId/...`) también la elige, así "La casa" y "Mis publicaciones"
 * siguen a la que se está mirando.
 *
 * <p>Al salir o entrar con otra cuenta se olvida todo (`SessionReset`): las casas de una
 * persona no le aparecen a la siguiente.
 */
@Injectable({ providedIn: 'root' })
export class FieldHouseStore {
  private readonly developments = inject(DevelopmentsService);
  private readonly auth = inject(AuthService);

  private readonly housesSignal = signal<AssignedHouse[]>([]);
  private readonly selectedId = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private request: Observable<AssignedHouse[]> | null = null;
  /** Sube con cada reseteo: una respuesta pedida en la sesión anterior se descarta. */
  private generation = 0;
  /** Algo cambió el avance, las etapas o los lugares de una casa: la próxima carga pide de nuevo. */
  private stale = false;

  readonly houses = this.housesSignal.asReadonly();
  readonly selected = computed<AssignedHouse | null>(() =>
    this.housesSignal().find(house => house.unitId === this.selectedId()) ?? this.housesSignal()[0] ?? null);

  constructor() {
    inject(SessionReset).onReset(() => {
      this.generation++;
      this.stale = false;
      this.request = null;
      this.housesSignal.set([]);
      this.selectedId.set(null);
      this.loading.set(false);
      this.error.set(null);
    });
  }

  /**
   * Carga las casas una vez; de nuevo con `force` (al reintentar) o si algo las dejó viejas
   * (`invalidate`). Mientras vuelven, se siguen mostrando las que había.
   */
  load(force = false): Observable<AssignedHouse[]> {
    if (this.request && !force && !this.stale) {
      return this.request;
    }
    this.stale = false;
    if (this.selectedId() === null) {
      this.selectedId.set(this.readSelected());
    }
    const generation = this.generation;
    this.loading.set(true);
    this.error.set(null);
    this.request = this.developments.myWork().pipe(
      tap({
        next: houses => {
          if (generation !== this.generation) {
            return;
          }
          this.housesSignal.set(houses);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          if (generation !== this.generation) {
            return;
          }
          this.loading.set(false);
          this.error.set(errorMessageOf(error, 'No se pudieron cargar tus casas.'));
          this.request = null;
        },
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.request;
  }

  /**
   * Lo que tiene Hoy de cada casa (avance, etapa en curso, lugares, lo último) quedó viejo: se
   * llama después de publicar un avance, aprobar un audio, subir material o cambiar etapas,
   * lugares o asignaciones. Hoy lo vuelve a pedir la próxima vez que se abre.
   */
  invalidate(): void {
    this.stale = true;
  }

  /** Elige una casa y la recuerda. Un id que no es de sus casas no cambia nada. */
  select(unitId: string): void {
    if (this.housesSignal().length > 0 && !this.housesSignal().some(house => house.unitId === unitId)) {
      return;
    }
    this.selectedId.set(unitId);
    const key = this.storageKey();
    if (!key) {
      return;
    }
    try {
      localStorage.setItem(key, unitId);
    } catch {
      // Sin almacenamiento, la elección dura lo que dura la sesión.
    }
  }

  private storageKey(): string | null {
    const userId = this.auth.currentUser()?.id;
    return userId ? userStorageKey(SELECTED_KEY, userId) : null;
  }

  private readSelected(): string | null {
    const key = this.storageKey();
    if (!key) {
      return null;
    }
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }
}
