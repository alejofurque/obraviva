import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { Unit, UnseenCount } from '../api/api.models';
import { ActivityService } from '../api/activity.service';
import { DevelopmentsService } from '../api/developments.service';
import { SessionReset } from './session-reset';
import { UserContextService } from './user-context.service';

/** Pantallas que no van en el marco del dueño: el campo del encargado y lo del comprador. */
const OUTSIDE = [/^\/hoy(\/|$|\?)/, /^\/mi-propiedad(\/|$|\?)/, /^\/mis-propiedades(\/|$|\?)/];

/** El desarrollo abierto y si se está en sus casas, leídos de la dirección. */
export function openPlace(url: string): { developmentId: string | null; inHouses: boolean } {
  const match = /^\/obras\/([^/?#]+)(\/casas(\/|$|\?))?/.exec(url);
  return { developmentId: match?.[1] ?? null, inHouses: !!match?.[2] };
}

/**
 * Lo que necesita el menú lateral del dueño, por encima de los espacios del desarrollo y de
 * la casa (que son rutas hermanas, cada una con su WorkStore): qué desarrollo está abierto,
 * sus casas y lo sin ver de cada desarrollo del que es dueño.
 *
 * <p>El marco del dueño lo usa quien es dueño de al menos una obra, fuera de "Hoy" y de las
 * pantallas del comprador. El encargado sigue con su marco de siempre.
 */
@Injectable({ providedIn: 'root' })
export class OwnerNavStore {

  private readonly router = inject(Router);
  private readonly context = inject(UserContextService);
  private readonly developmentsService = inject(DevelopmentsService);
  private readonly activity = inject(ActivityService);

  readonly url = toSignal(
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), map(event => event.urlAfterRedirects)),
    { initialValue: this.router.url },
  );

  readonly developments = this.context.developments;
  readonly owns = computed(() => this.developments().some(development => development.role === 'OWNER'));
  /** Si la pantalla actual va en el marco del dueño, con el menú lateral. */
  readonly active = computed(() => this.owns() && !OUTSIDE.some(pattern => pattern.test(this.url())));

  private readonly place = computed(() => openPlace(this.url()));
  readonly openId = computed(() => this.place().developmentId);
  readonly inHouses = computed(() => this.place().inHouses);

  private readonly unitsSignal = signal<{ developmentId: string; units: Unit[] } | null>(null);
  /** Las casas del desarrollo abierto, en su orden; vacías mientras cargan o si fallan. */
  readonly units = computed(() => {
    const loaded = this.unitsSignal();
    return loaded && loaded.developmentId === this.openId() ? loaded.units : [];
  });

  private readonly unseenSignal = signal<Record<string, UnseenCount>>({});
  readonly unseen = this.unseenSignal.asReadonly();
  /** Sube con cada reseteo: una respuesta pedida en la sesión anterior se descarta. */
  private generation = 0;

  constructor() {
    // Las casas y lo sin ver son de quien está adentro: con otra sesión, se piden de nuevo.
    inject(SessionReset).onReset(() => {
      this.generation++;
      this.unitsSignal.set(null);
      this.unseenSignal.set({});
    });
    // Las casas del desarrollo abierto, una vez por desarrollo.
    effect(() => {
      const id = this.openId();
      if (!this.active() || !id || untracked(() => this.unitsSignal()?.developmentId) === id) {
        return;
      }
      this.reloadUnits(id);
    });
    // Lo sin ver de cada desarrollo del que es dueño: al cargar el contexto y cada vez que se
    // cambia de desarrollo (o se vuelve al inicio), así lo nuevo de los otros aparece sin
    // recargar. El abierto lo pone al día su WorkStore.
    effect(() => {
      const openId = this.openId();
      if (!this.active()) {
        return;
      }
      for (const development of this.developments()) {
        if (development.role === 'OWNER' && development.id !== openId) {
          this.refreshUnseen(development.id);
        }
      }
    });
  }

  reloadUnits(developmentId: string): void {
    const generation = this.generation;
    this.developmentsService.units(developmentId).subscribe({
      next: units => generation === this.generation && this.unitsSignal.set({ developmentId, units }),
      error: () => generation === this.generation && this.unitsSignal.set({ developmentId, units: [] }),
    });
  }

  /** El contador es un aviso: si falla, el desarrollo queda sin número. */
  refreshUnseen(developmentId: string): void {
    const generation = this.generation;
    this.activity.unseenCount(developmentId).subscribe({
      next: count => generation === this.generation && this.setUnseen(developmentId, count),
      error: () => undefined,
    });
  }

  setUnseen(developmentId: string, count: UnseenCount): void {
    this.unseenSignal.update(current => ({ ...current, [developmentId]: count }));
  }
}
