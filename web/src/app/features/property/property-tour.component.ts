import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { PublicCapture } from '../../core/api/api.models';
import { BackNavigation } from '../../core/routing/back-navigation';
import { SplatViewerComponent, ViewerCapture } from '../viewer/splat-viewer.component';
import { placeMediaAt } from './places-derivations';
import {
  busiestZone,
  captureAtMonth,
  captureIndexAt,
  captureMonths,
  longMonth,
  monthStop,
  propertyTitle,
  shortMonth,
  zonesOf,
} from './property-derivations';
import { PropertyViewStore } from './property-view.store';
import { TourMonthMediaComponent } from './tour-month-media.component';

/** Nombre del grupo para las capturas sin ambiente, cuando otras sí lo tienen. */
const GENERAL_ZONE = 'General';

interface IndexedCapture {
  capture: PublicCapture;
  /** Posición en la lista completa: identifica la escena sin exponer datos internos. */
  index: number;
}

/**
 * Recorrido 3D a pantalla completa, con viaje en el tiempo por meses.
 *
 * <p>Cada mes de la línea de tiempo muestra, por cada sector, su captura más reciente hasta
 * ese mes: un sector que no se volvió a grabar sigue con la anterior, y su chip dice de
 * cuándo es. Al cambiar de mes la cámara queda quieta y la obra cambia alrededor. Si se
 * llega desde un avance de la historia, se abre en el mes de esa fecha.
 */
@Component({
  selector: 'app-property-tour',
  imports: [DatePipe, SplatViewerComponent, TourMonthMediaComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown.escape)': 'exit()',
  },
  template: `
    @if (store.view(); as view) {
      @if (visible().length === 0) {
        <div class="empty">
          <p class="empty-title">Todavía no hay recorridos 3D de esta obra.</p>
          <button type="button" class="ov-btn ov-btn--on-media" (click)="exit()">Volver</button>
        </div>
      } @else {
        @if (hintVisible()) {
          <div class="hint" role="status">
            <p>
              Arrastrá para mirar alrededor y acercate con la rueda o pellizcando.
              @if (months().length > 1) { Con la línea de abajo viajás en el tiempo: cada punto es un mes de obra. }
              @else if (zones().length > 1) { Arriba elegís otro ambiente de la casa. }
            </p>
            <button type="button" class="glass ov-hit" (click)="hintVisible.set(false)">Entendido</button>
          </div>
        }

        <app-splat-viewer
          #viewer
          class="scene"
          [captures]="viewerCaptures()"
          [activeIndex]="activeIndex()"
          [crossfade]="true"
        />

        <div class="top" [class.hidden]="!controlsVisible()">
          <div class="title">
            <p class="unit">{{ title(view) }}</p>
            @if (active(); as current) {
              <p class="where">
                @if (current.capture.common) { Espacios de {{ view.developmentName }} · }
                {{ zone() ?? 'Recorrido' }} · {{ current.capture.date | date: 'longDate' }}
              </p>
            }
          </div>
          <button type="button" class="glass ov-hit" (click)="exit()">Salir del recorrido</button>
        </div>

        <div class="side" [class.hidden]="!controlsVisible()">
          <button type="button" class="glass ov-hit" (click)="toggleFullscreen()">
            {{ fullscreen() ? 'Salir de pantalla completa' : 'Pantalla completa' }}
          </button>
          <button type="button" class="glass ov-hit" (click)="viewer.recenter()">Recentrar</button>
        </div>

        <div class="bottom">
          @if (controlsVisible()) {
            @if (monthMedia(); as media) {
              <app-tour-month-media [media]="media" [place]="zone() ?? 'General'" (failed)="store.renewAfterFailure($event)" />
            }
            @if (notice(); as text) {
              <p class="notice" role="status">{{ text }}</p>
            }

            @if (zones().length > 1) {
              <div class="zones" role="radiogroup" aria-label="Ambientes">
                @for (sector of sectors(); track sector.zone) {
                  <button type="button" role="radio" class="chip ov-hit" [class.active]="sector.zone === zone()"
                          [attr.aria-checked]="sector.zone === zone()" [disabled]="!sector.item"
                          (click)="selectZone(sector.zone)">
                    {{ sector.zone }}@if (!sector.item) {<span class="chip-note"> · Sin captura aún</span>} @else if (sector.from; as from) {<span class="chip-note"> · {{ from }}</span>}
                  </button>
                }
              </div>
            }

            @if (months().length > 1) {
              <div class="time glass-panel">
                <div class="time-head">
                  <span>Viajá en el tiempo</span>
                  @if (active(); as current) {
                    <span class="time-title">{{ current.capture.description || current.capture.stageName || '' }}</span>
                  }
                </div>
                <ol class="track" role="radiogroup" aria-label="Meses" (keydown)="onMonthKey($event)">
                  @for (item of months(); track item) {
                    <li role="none">
                      <button type="button" role="radio" class="stop" [class.active]="item === month()"
                              [attr.aria-checked]="item === month()" [attr.tabindex]="item === month() ? 0 : -1"
                              [attr.aria-label]="longMonth(item)" [attr.data-month]="item" (click)="selectMonth(item)">
                        <span class="dot" aria-hidden="true"></span>
                        <span class="month">{{ monthStop(item) }}</span>
                      </button>
                    </li>
                  }
                </ol>
              </div>
            }
          }
          <button type="button" class="toggle ov-hit" (click)="controlsVisible.set(!controlsVisible())">
            {{ controlsVisible() ? 'Ocultar controles' : 'Mostrar controles' }}
          </button>
        </div>
      }
    }
  `,
  styles: `
    :host {
      position: fixed;
      inset: 0;
      z-index: 100;
      display: block;
      background: var(--ov-ink);
      color: var(--ov-on-media);
    }

    .scene {
      position: absolute;
      inset: 0;
      display: block;
    }

    .top,
    .side,
    .bottom {
      position: absolute;
      left: var(--ov-gutter);
      right: var(--ov-gutter);
      transition: opacity var(--ov-duration) var(--ov-ease);
    }

    .hidden {
      opacity: 0;
      pointer-events: none;
    }

    .top {
      top: var(--ov-space-5);
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-4);
      pointer-events: none;
    }

    .top > * {
      pointer-events: auto;
    }

    .unit {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .where {
      font-size: var(--ov-text-sm);
      opacity: 0.85;
    }

    .title {
      text-shadow: 0 1px 8px var(--ov-veil);
    }

    /* Un botón no se parte en dos líneas: "Salir del recorrido" deja lugar al texto de al lado. */
    .glass {
      flex: none;
      white-space: nowrap;
    }

    .glass,
    .chip,
    .toggle {
      min-height: 2.5rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-sm);
      backdrop-filter: blur(12px);
      cursor: pointer;
    }

    .side {
      top: 45%;
      left: auto;
      display: grid;
      gap: var(--ov-space-2);
      justify-items: end;
    }

    .bottom {
      bottom: var(--ov-space-4);
      display: grid;
      gap: var(--ov-space-3);
      justify-items: stretch;
    }

    .zones {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .chip.active {
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .glass-panel {
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-glass);
      backdrop-filter: blur(16px);
    }

    .time-head {
      display: flex;
      justify-content: space-between;
      gap: var(--ov-space-4);
      font-size: var(--ov-text-sm);
    }

    .time-title {
      font-weight: var(--ov-weight-medium);
      text-align: right;
    }

    /* Un punto por mes. En el teléfono, si no entran, la barra se desplaza de costado. */
    .track {
      display: flex;
      margin: var(--ov-space-4) 0 0;
      padding: 0;
      overflow-x: auto;
      list-style: none;
      scrollbar-width: thin;
    }

    .track li {
      position: relative;
      flex: 1 0 4.5rem;
    }

    /* La línea que une los puntos, de mes en mes. */
    .track li::before {
      content: '';
      position: absolute;
      top: 1.1875rem;
      left: 0;
      right: 0;
      height: 1px;
      background: var(--ov-on-media-line);
    }

    .track li:first-child::before {
      left: 50%;
    }

    .track li:last-child::before {
      right: 50%;
    }

    .stop {
      position: relative;
      display: grid;
      justify-items: center;
      align-content: start;
      gap: var(--ov-space-1);
      width: 100%;
      min-width: 2.75rem;
      min-height: 2.75rem;
      padding: 0.75rem 0 0;
      border: 0;
      background: none;
      color: var(--ov-on-media-muted);
      font: inherit;
      font-size: var(--ov-text-xs);
      white-space: nowrap;
      cursor: pointer;
    }

    .stop:focus-visible {
      outline: 2px solid var(--ov-on-media);
      outline-offset: 2px;
      border-radius: var(--ov-radius-md);
    }

    .chip:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .chip-note {
      opacity: 0.8;
    }

    .notice {
      justify-self: start;
      padding: var(--ov-space-2) var(--ov-space-4);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      font-size: var(--ov-text-sm);
      backdrop-filter: blur(12px);
    }

    .dot {
      width: 0.875rem;
      height: 0.875rem;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
    }

    .stop.active {
      color: var(--ov-on-media);
      font-weight: var(--ov-weight-semibold);
    }

    .stop.active .dot {
      box-shadow: 0 0 0 4px var(--ov-on-media-line);
    }

    .toggle {
      justify-self: center;
      min-height: 2rem;
      font-size: var(--ov-text-xs);
    }

    .hint {
      position: absolute;
      top: 50%;
      left: 50%;
      z-index: 1;
      display: grid;
      gap: var(--ov-space-4);
      justify-items: center;
      width: min(26rem, calc(100% - 2 * var(--ov-gutter)));
      padding: var(--ov-space-5) var(--ov-space-6);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-glass);
      backdrop-filter: blur(16px);
      text-align: center;
      transform: translate(-50%, -50%);
    }

    .empty {
      display: grid;
      place-content: center;
      justify-items: center;
      gap: var(--ov-space-4);
      height: 100%;
      text-align: center;
    }

    .empty-title {
      font-size: var(--ov-text-lg);
    }
  `,
})
export class PropertyTourComponent implements OnInit, OnDestroy {

  protected readonly store = inject(PropertyViewStore);
  protected readonly title = propertyTitle;
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);


  protected readonly zone = signal<string | null>(null);
  /** Mes elegido en la línea de tiempo ("2026-07"). */
  protected readonly month = signal<string | null>(null);
  /** Aviso de una línea cuando el sector activo no tenía captura en el mes elegido. */
  protected readonly notice = signal<string | null>(null);
  protected readonly controlsVisible = signal(true);
  /** Primera vez: cómo se mueve uno adentro. Se va sola o con "Entendido". */
  protected readonly hintVisible = signal(true);
  protected readonly fullscreen = signal(false);

  private readonly all = computed<IndexedCapture[]>(() =>
    (this.store.view()?.captures ?? []).map((capture, index) => ({ capture, index })));

  /** Ambientes a elegir. Las capturas sin ambiente van a "General" sólo si otras lo tienen. */
  protected readonly zones = computed(() => {
    const captures = this.all().map(item => item.capture);
    const named = zonesOf(captures, this.store.view()?.sectors ?? []);
    if (named.length > 0 && captures.some(capture => !capture.zone)) {
      named.push(GENERAL_ZONE);
    }
    return named;
  });

  protected readonly visible = computed(() => {
    const zone = this.zone();
    if (zone === null || this.zones().length === 0) {
      return this.all();
    }
    return this.all().filter(item => (item.capture.zone ?? GENERAL_ZONE) === zone);
  });

  /** Los meses con capturas, del más viejo al más nuevo: los puntos de la línea de tiempo. */
  protected readonly months = computed(() => captureMonths(this.all().map(item => item.capture)));

  /**
   * Qué muestra cada sector en el mes elegido: su captura más reciente hasta ese mes.
   * {@code from} dice de qué mes es cuando no es del elegido ("jul"); sin captura, el
   * sector queda deshabilitado.
   */
  protected readonly sectors = computed(() => {
    const month = this.month();
    return this.zones().map(zone => {
      const own = this.all().filter(item => (item.capture.zone ?? GENERAL_ZONE) === zone);
      const item = month ? shownAt(own, month) : own.at(-1) ?? null;
      const captured = item?.capture.date.slice(0, 7) ?? null;
      return { zone, item, from: captured !== null && month !== null && captured < month ? shortMonth(captured) : null };
    });
  });

  /**
   * La escena que se ve: la captura del sector activo para el mes elegido. Si entre dos
   * meses es la misma, el índice no cambia y el visor no la recarga ni la funde.
   */
  protected readonly activeIndex = computed(() => {
    const visible = this.visible();
    const month = this.month();
    const shown = month ? shownAt(visible, month) : visible.at(-1) ?? null;
    return shown ? visible.indexOf(shown) : Math.max(0, visible.length - 1);
  });

  protected readonly active = computed(() => this.visible()[this.activeIndex()] ?? null);

  /**
   * Las fotos y videos del lugar y el mes que se ven, para "Fotos de este mes". Salen de
   * `places` de la misma vista: no hay otro pedido. Nulo si ese mes el lugar no tiene.
   */
  protected readonly monthMedia = computed(() => {
    const month = this.month();
    return month ? placeMediaAt(this.store.view()?.places ?? [], this.zone() ?? GENERAL_ZONE, month) : null;
  });

  protected readonly monthStop = monthStop;
  protected readonly longMonth = longMonth;

  protected readonly viewerCaptures = computed<ViewerCapture[]>(() =>
    this.visible().map(({ capture, index }) => ({
      id: `captura-${index}`,
      url: capture.splatUrl,
      transform: {
        position: { x: capture.transform.positionX, y: capture.transform.positionY, z: capture.transform.positionZ },
        rotationDeg: { x: capture.transform.rotationX, y: capture.transform.rotationY, z: capture.transform.rotationZ },
        scale: capture.transform.scale,
      },
    })));

  private readonly onFullscreenChange = () => this.fullscreen.set(document.fullscreenElement !== null);
  private previousOverflow = '';

  private readonly backNavigation = inject(BackNavigation);

  constructor() {
    // El mes y el ambiente quedan en la dirección (sin sumar entradas al historial): volver
    // con el atrás del navegador o recargar abre el mismo punto del recorrido.
    effect(() => {
      const month = this.month();
      const zone = this.zone();
      if (!month) {
        return;
      }
      untracked(() => void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { mes: month, sector: zone, fecha: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      }));
    });
  }

  ngOnInit(): void {
    this.previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    document.addEventListener('fullscreenchange', this.onFullscreenChange);

    // Las URLs firmadas vencen: antes de cargar escenas, la vista se renueva si hace falta.
    this.store.ensureFresh()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const params = this.route.snapshot.queryParamMap;
        const month = params.get('mes');
        if (month && this.selectMonthAndSector(month, params.get('sector'))) {
          return;
        }
        this.selectInitial(params.get('fecha'));
      });
    this.hintTimer = setTimeout(() => this.hintVisible.set(false), 7000);
  }

  private hintTimer: ReturnType<typeof setTimeout> | undefined;

  ngOnDestroy(): void {
    clearTimeout(this.hintTimer);
    document.body.style.overflow = this.previousOverflow;
    document.removeEventListener('fullscreenchange', this.onFullscreenChange);
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    }
  }

  /** Al cambiar de ambiente se ve su captura del mes elegido; la cámara no se mueve. */
  protected selectZone(zone: string): void {
    this.zone.set(zone);
    this.notice.set(null);
  }

  /**
   * Cambia de mes sin mover la cámara. Si el sector activo todavía no tenía captura en ese
   * mes, pasa al primero que sí tenga y lo avisa en una línea.
   */
  protected selectMonth(month: string): void {
    this.month.set(month);
    const current = this.zone();
    const sectors = this.sectors();
    if (current !== null && sectors.length > 0 && !sectors.find(sector => sector.zone === current)?.item) {
      const first = sectors.find(sector => sector.item !== null);
      if (first) {
        this.zone.set(first.zone);
        this.notice.set(`${current} todavía no tenía captura en ${longMonth(month)}: se muestra ${first.zone}.`);
        return;
      }
    }
    this.notice.set(null);
  }

  /** Flechas, Inicio y Fin recorren los meses, como un grupo de opciones. */
  protected onMonthKey(event: KeyboardEvent): void {
    const months = this.months();
    const current = months.indexOf(this.month() ?? '');
    const next = {
      ArrowLeft: current - 1, ArrowUp: current - 1, ArrowRight: current + 1, ArrowDown: current + 1,
      Home: 0, End: months.length - 1,
    }[event.key];
    if (next === undefined) {
      return;
    }
    event.preventDefault();
    const month = months[Math.min(Math.max(next, 0), months.length - 1)];
    this.selectMonth(month);
    (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`.stop[data-month="${month}"]`)?.focus();
  }

  protected toggleFullscreen(): void {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    } else {
      (this.host.nativeElement as HTMLElement).requestFullscreen?.().catch(() => {});
    }
  }

  /** "Volver": a donde se estaba (Mi casa, un lugar, la historia) o a Mi casa si se entró directo. */
  protected exit(): void {
    this.backNavigation.back(this.router.createUrlTree(this.store.basePath(), { queryParams: this.store.navQuery() }));
  }

  /**
   * Abre en un mes y un sector pedidos desde la historia ("Recorridos 3D de este mes"). Si
   * el sector no tenía captura en ese mes, queda el primero que sí. Falso si el mes no tiene
   * capturas: entonces se abre como siempre.
   */
  private selectMonthAndSector(month: string, sector: string | null): boolean {
    if (!this.months().includes(month)) {
      return false;
    }
    if (sector !== null && this.zones().includes(sector)) {
      this.zone.set(sector);
    } else if (this.zones().length > 0) {
      this.zone.set(this.zones()[0]);
    }
    this.selectMonth(month);
    return true;
  }

  /**
   * Abre en el último mes. Sin fecha, en el ambiente con más recorridos: así lo primero que
   * se ve ya permite viajar en el tiempo. Con fecha (desde un avance de la historia), en el
   * ambiente y el mes de la captura de ese día.
   */
  private selectInitial(date: string | null): void {
    const all = this.all();
    if (all.length === 0) {
      return;
    }
    const busiest = date ? null : busiestZone(all.map(item => item.capture));
    if (busiest !== null) {
      this.zone.set(busiest);
      this.month.set(this.months().at(-1) ?? null);
      return;
    }
    const target = date ? captureIndexAt(all.map(item => item.capture), date) : all.length - 1;
    const targetCapture = all[target];

    if (this.zones().length > 0) {
      this.zone.set(targetCapture.capture.zone ?? GENERAL_ZONE);
    }
    this.month.set(targetCapture.capture.date.slice(0, 7));
  }
}

/** La captura que se ve de un grupo en un mes, aplicando la regla de {@link captureAtMonth}. */
function shownAt(items: IndexedCapture[], month: string): IndexedCapture | null {
  return captureAtMonth(items.map(item => ({ date: item.capture.date, item })), month)?.item ?? null;
}
