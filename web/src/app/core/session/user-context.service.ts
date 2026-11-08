import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { finalize, map, shareReplay, tap } from 'rxjs/operators';
import { Development, MyUnitSummary } from '../api/api.models';
import { DevelopmentsService } from '../api/developments.service';
import { MyPropertyService } from '../api/my-property.service';
import { AuthService } from '../auth/auth.service';
import { SessionReset } from './session-reset';

/**
 * Qué tiene el usuario en ObraViva: obras que administra o sigue como encargado, y
 * propiedades que compró.
 *
 * <p>No hay un rol global: los permisos son por obra y por unidad, y una misma persona
 * puede ser compradora en una obra y dueña de otra. La navegación se arma con lo que
 * la persona tiene, no con una etiqueta de usuario.
 */
@Injectable({ providedIn: 'root' })
export class UserContextService {

  private readonly auth = inject(AuthService);
  private readonly developmentsService = inject(DevelopmentsService);
  private readonly myPropertyService = inject(MyPropertyService);

  private readonly developmentsSignal = signal<Development[]>([]);
  private readonly unitsSignal = signal<MyUnitSummary[]>([]);

  /** Usuario para el que se cargó el contexto: si cambia la sesión, se vuelve a cargar. */
  private loadedFor: string | null = null;
  private inFlight: Observable<void> | null = null;
  /** Sube con cada reseteo: una respuesta pedida en la sesión anterior se descarta. */
  private generation = 0;

  constructor() {
    inject(SessionReset).onReset(() => this.reset());
  }

  readonly developments = this.developmentsSignal.asReadonly();
  readonly units = this.unitsSignal.asReadonly();

  readonly managesDevelopments = computed(() => this.developmentsSignal().length > 0);

  /** Encargado en algún desarrollo: tiene trabajo de campo. */
  readonly supervises = computed(() => this.developmentsSignal().some(development => development.role === 'MEMBER'));

  /** Sólo encargado, sin desarrollos propios: su inicio es el trabajo del día. */
  readonly onlySupervises = computed(() =>
    this.developmentsSignal().length > 0 && this.developmentsSignal().every(development => development.role === 'MEMBER'));
  readonly ownsUnits = computed(() => this.unitsSignal().length > 0);

  /**
   * Adónde entra la persona. Con una sola cosa, directo a ella; con obras y propiedades,
   * al inicio que las reúne; sin nada todavía, también al inicio, que lo explica.
   */
  readonly homeUrl = computed<string[]>(() => {
    const developments = this.developmentsSignal();
    const units = this.unitsSignal();
    if (units.length > 0 && developments.length === 0) {
      return units.length === 1 ? ['/mi-propiedad', units[0].unitId] : ['/mis-propiedades'];
    }
    if (developments.length > 0 && units.length === 0) {
      return this.onlySupervises() ? ['/hoy'] : ['/desarrollos'];
    }
    return ['/inicio'];
  });

  /** Carga el contexto si todavía no está cargado para el usuario de la sesión actual. */
  ensureLoaded(): Observable<void> {
    const userId = this.auth.currentUser()?.id ?? null;
    if (userId === null) {
      this.reset();
      return of(undefined);
    }
    if (this.loadedFor === userId) {
      return of(undefined);
    }
    if (!this.inFlight) {
      const generation = this.generation;
      this.inFlight = forkJoin({
        developments: this.developmentsService.list(),
        units: this.myPropertyService.myUnits(),
      }).pipe(
        tap(({ developments, units }) => {
          if (generation !== this.generation) {
            return;
          }
          this.developmentsSignal.set(developments);
          this.unitsSignal.set(units);
          this.loadedFor = userId;
        }),
        map(() => undefined),
        // Con éxito o con error, la próxima llamada decide de nuevo.
        finalize(() => (this.inFlight = null)),
        shareReplay(1),
      );
    }
    return this.inFlight;
  }

  /** Fuerza la recarga, por ejemplo después de crear una obra. */
  refresh(): Observable<void> {
    this.loadedFor = null;
    return this.ensureLoaded();
  }

  reset(): void {
    this.generation++;
    this.developmentsSignal.set([]);
    this.unitsSignal.set([]);
    this.loadedFor = null;
    this.inFlight = null;
  }
}
