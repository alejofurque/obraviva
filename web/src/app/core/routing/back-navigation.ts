import { Location } from '@angular/common';
import { Directive, Injectable, computed, inject, input } from '@angular/core';
import { NavigationCancel, NavigationEnd, NavigationError, NavigationSkipped, NavigationStart, Params, Router, UrlTree } from '@angular/router';

/** Clave propia en `history.state`: cuántas pantallas de la app hay antes de esta entrada. */
const DEPTH_KEY = 'obravivaDepth';

/** Qué le hace una navegación al historial del navegador. */
type HistoryEffect = 'push' | 'replace' | 'restore' | 'none';

/**
 * La regla de "Volver" de toda la app: si se llegó desde otra pantalla de la app, vuelve ahí
 * (como el atrás del navegador); si se entró directo, va a un lugar fijo, que la pantalla da.
 *
 * <p>Cuenta las pantallas de la app que hay detrás de la actual con los eventos públicos del
 * router: una navegación que agrega una entrada suma uno; una con `replaceUrl` (la inicial,
 * los filtros, los desvíos de una guarda) deja la cuenta igual; un atrás o adelante del
 * navegador (`popstate`) toma la cuenta guardada en esa entrada. La cuenta se guarda en
 * `history.state` de cada entrada, así sobrevive a F5: recargar una pantalla a la que se
 * llegó desde otra sigue pudiendo volver; entrar directo arranca en cero.
 *
 * <p>Se crea al arrancar la app (`app.config.ts`) para ver la primera navegación.
 */
@Injectable({ providedIn: 'root' })
export class BackNavigation {
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  /** Pantallas de la app antes de la actual. La de la carga de la página viene de su entrada. */
  private depth = this.storedDepth();
  private readonly pending = new Map<number, HistoryEffect>();

  constructor() {
    this.router.events.subscribe(event => {
      if (event instanceof NavigationStart) {
        this.pending.set(event.id, this.effectOf(event));
      } else if (event instanceof NavigationEnd) {
        this.settle(this.pending.get(event.id) ?? 'push');
        this.pending.delete(event.id);
      } else if (event instanceof NavigationCancel || event instanceof NavigationError || event instanceof NavigationSkipped) {
        this.pending.delete(event.id);
      }
    });
  }

  canGoBack(): boolean {
    return this.depth > 0;
  }

  back(fallback: UrlTree | string): void {
    if (this.canGoBack()) {
      this.location.back();
    } else {
      void this.router.navigateByUrl(fallback);
    }
  }

  private effectOf(event: NavigationStart): HistoryEffect {
    if (event.navigationTrigger === 'popstate') {
      return 'restore';
    }
    const extras = this.router.currentNavigation()?.extras;
    if (extras?.skipLocationChange) {
      return 'none';
    }
    return extras?.replaceUrl ? 'replace' : 'push';
  }

  private settle(effect: HistoryEffect): void {
    if (effect === 'none') {
      return;
    }
    if (effect === 'restore') {
      this.depth = this.storedDepth();
    } else if (effect === 'push') {
      this.depth++;
    }
    // El router ya escribió su estado en la entrada: se le suma la cuenta sin pisar lo suyo.
    const state = (this.location.getState() as Record<string, unknown> | null) ?? {};
    this.location.replaceState(this.location.path(true), '', { ...state, [DEPTH_KEY]: this.depth });
  }

  private storedDepth(): number {
    const stored = (this.location.getState() as Record<string, unknown> | null)?.[DEPTH_KEY];
    return typeof stored === 'number' && stored > 0 ? stored : 0;
  }
}

/**
 * Un enlace "Volver" que sigue la regla: `<a [appBack]="['/hoy']">`. El `href` es el lugar
 * fijo, así abrir en otra pestaña o con el botón del medio va ahí.
 */
@Directive({
  selector: 'a[appBack]',
  host: { '[attr.href]': 'href()', '(click)': 'go($event)' },
})
export class BackLinkDirective {
  private readonly router = inject(Router);
  private readonly navigation = inject(BackNavigation);

  /** El lugar fijo, para cuando se entró directo. */
  readonly appBack = input.required<readonly unknown[] | string>();
  readonly backQuery = input<Params | null>(null);

  private readonly fallback = computed(() => {
    const target = this.appBack();
    return typeof target === 'string'
      ? this.router.parseUrl(target)
      : this.router.createUrlTree([...target], { queryParams: this.backQuery() ?? undefined });
  });
  protected readonly href = computed(() => this.router.serializeUrl(this.fallback()));

  protected go(event: MouseEvent): boolean {
    // Con una tecla o con otro botón, el navegador abre el lugar fijo donde se le pidió.
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return true;
    }
    event.preventDefault();
    this.navigation.back(this.fallback());
    return false;
  }
}
