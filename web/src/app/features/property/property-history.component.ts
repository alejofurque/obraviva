import { DatePipe, formatDate } from '@angular/common';
import { ChangeDetectionStrategy, Component, LOCALE_ID, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { replaceQuery } from '../../core/routing/query-state';
import { PublicProgressEntry } from '../../core/api/api.models';
import { EmptyStateComponent } from '../../shared/ui/page-states.component';
import { VariantPipe, variantOf } from '../../shared/media/variant.pipe';
import { PhotoViewerComponent, ViewerPhoto } from './photo-viewer.component';
import { historyMonths, monthHeading } from './property-derivations';
import { PropertyViewStore } from './property-view.store';

/** Meses que arrancan abiertos: los más recientes, que es lo que se viene a mirar. */
const OPEN_BY_DEFAULT = 2;
/** Miniaturas por avance; con más fotos, la última dice cuántas quedan. */
const MAX_THUMBS = 4;

/**
 * La historia de la obra agrupada por mes, del más nuevo al más viejo. Cada avance es una
 * fila liviana: el día, la etapa, el texto y sus fotos. Los recorridos 3D van por mes, con un
 * chip por sector que abre el recorrido en ese mes y ese sector.
 *
 * <p>Se muestra también lo incómodo: si un avance fue corregido lo dice, y un avance
 * publicado desde un audio lo aclara. La transparencia incluye cómo llegó cada dato. Lo que se
 * hizo antes de empezar el seguimiento (la carga inicial) va al final, "Antes del seguimiento",
 * con mes y año: sus fechas son aproximadas.
 */
/** Qué ver según la dirección: `?ver=casa` o `?ver=barrio`; sin eso, todo. */
function filterFromQuery(value: string | null): 'ALL' | 'HOUSE' | 'COMMON' {
  return value === 'casa' ? 'HOUSE' : value === 'barrio' ? 'COMMON' : 'ALL';
}

@Component({
  selector: 'app-property-history',
  imports: [DatePipe, RouterLink, EmptyStateComponent, PhotoViewerComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.view(); as view) {
      <div class="ov-page ov-page--narrow history">
        <header class="intro">
          <h2 class="ov-display">La historia de tu casa</h2>
          <p class="ov-lead">Todo lo que se hizo, desde el primer día hasta hoy, con sus fotos.</p>
          @if (hasCommon()) {
            <div class="filters" role="radiogroup" aria-label="Qué mostrar">
              @for (option of filters; track option.value) {
                <button type="button" role="radio" class="filter ov-hit" [class.active]="filter() === option.value"
                        [attr.aria-checked]="filter() === option.value" (click)="chooseFilter(option.value)">{{ option.label }}</button>
              }
            </div>
          }
        </header>

        @if (months().length === 0) {
          <app-empty-state
            title="Todavía no hay avances publicados"
            message="Cuando se publique el primer avance de la obra, va a aparecer acá con sus fotos." />
        } @else {
          <ol class="months">
            @for (group of months(); track group.month; let i = $index) {
              <li class="month" [class.open]="isOpen(group.month, i)">
                <button type="button" class="month-head ov-hit" [attr.aria-expanded]="isOpen(group.month, i)"
                        [attr.aria-controls]="'month-' + group.month" (click)="toggle(group.month, i)">
                  <span class="month-name">{{ group.beforeTracking ? 'Antes del seguimiento' : heading(group.month) }}</span>
                  <span class="month-count">{{ ' · ' + countOf(group.entries.length) }}</span>
                </button>

                @if (isOpen(group.month, i)) {
                  <div class="month-body" [id]="'month-' + group.month">
                    @if (group.sectors.length > 0) {
                      <div class="tours">
                        <span class="tours-label">Recorridos 3D de este mes</span>
                        <div class="chips">
                          @for (sector of group.sectors; track sector) {
                            <a class="chip ov-hit" [routerLink]="[...store.basePath(), 'recorrido']"
                               [queryParams]="{ ...store.navQuery(), mes: group.month, sector: sector }">{{ sector }}</a>
                          }
                        </div>
                      </div>
                    }

                    @if (group.entries.length > 0) {
                      <ol class="rows">
                        @for (entry of group.entries; track $index) {
                          <li class="entry">
                            <p class="meta">
                              <span class="day ov-num">{{ entry.date | date: (group.beforeTracking ? 'MMM y' : 'd MMM') }}</span>
                              @if (entry.stageName) {
                                <span class="ov-pill stage">{{ entry.stageName }}</span>
                              }
                              @if (entry.common) {
                                <span class="ov-pill ov-pill--accent">Novedad del barrio</span>
                              }
                              @if (entry.source === 'AUDIO') {
                                <span class="ov-pill ov-pill--outline">Desde un audio confirmado</span>
                              }
                              @if (entry.corrected) {
                                <span class="ov-pill ov-pill--outline">Corregido</span>
                              }
                            </p>
                            <p class="description">{{ entry.description }}</p>

                            @if (entry.photos.length > 0) {
                              <div class="thumbs">
                                @for (photo of entry.photos; track photo.url; let p = $index) {
                                  @if (p < maxThumbs) {
                                    <button type="button" class="thumb" (click)="openPhotos(entry, p)"
                                            [attr.aria-label]="'Ampliar foto ' + (p + 1) + ' de ' + entry.photos.length">
                                      <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" (error)="store.renewAfterFailure(photo.url)" />
                                      @if (p === maxThumbs - 1 && entry.photos.length > maxThumbs) {
                                        <span class="more">+{{ entry.photos.length - maxThumbs + 1 }}</span>
                                      }
                                    </button>
                                  }
                                }
                              </div>
                            }
                          </li>
                        }
                      </ol>
                    }
                  </div>
                }
              </li>
            }
          </ol>
        }
      </div>

      <app-photo-viewer #viewer [photos]="viewerPhotos()" alt="Foto ampliada del avance"
                        (failed)="store.renewAfterFailure($event)" />
    }
  `,
  styles: `
    .history {
      --ov-measure: 64rem;
    }

    .intro {
      display: grid;
      gap: var(--ov-space-3);
      padding-bottom: var(--ov-space-6);
      border-bottom: 1px solid var(--ov-line);
    }

    .filters {
      display: inline-flex;
      justify-self: start;
      gap: var(--ov-space-1);
      margin-top: var(--ov-space-2);
      padding: var(--ov-space-1);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .filter {
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      cursor: pointer;
    }

    .filter.active {
      background: var(--ov-surface);
      color: var(--ov-ink);
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
    }

    .filter:focus-visible,
    .month-head:focus-visible,
    .thumb:focus-visible,
    .chip:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .months,
    .rows {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    /* Un mes por bloque: en el teléfono el mes es el encabezado; en escritorio, la columna izquierda. */
    .month {
      display: grid;
      gap: var(--ov-space-2) var(--ov-space-7);
      padding: var(--ov-space-5) 0;
      border-bottom: 1px solid var(--ov-line);
    }

    .month-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      align-self: start;
      gap: var(--ov-space-1) var(--ov-space-1);
      white-space: pre-wrap;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .month-name {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .month-count {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .month-body {
      display: grid;
      gap: var(--ov-space-3);
      min-width: 0;
    }

    .tours {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2) var(--ov-space-3);
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
    }

    .tours-label {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .chip {
      display: inline-flex;
      align-items: center;
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-accent);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      text-decoration: none;
    }

    .chip:hover {
      background: var(--ov-accent);
      color: var(--ov-on-ink);
    }

    /* Fila liviana: separadores finos y nada de bloques. */
    .entry {
      display: grid;
      gap: var(--ov-space-2);
      padding: var(--ov-space-4) 0;
      border-top: 1px solid var(--ov-line);
    }

    .rows .entry:first-child {
      border-top: 0;
      padding-top: var(--ov-space-1);
    }

    .meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2);
    }

    .day {
      font-weight: var(--ov-weight-semibold);
    }

    .stage {
      font-weight: var(--ov-weight-medium);
    }

    .description {
      max-width: var(--ov-reading-max);
      line-height: var(--ov-leading-normal);
    }

    .thumbs {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 6.5rem));
      gap: var(--ov-space-2);
    }

    .thumb {
      position: relative;
      padding: 0;
      overflow: hidden;
      border: 0;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      aspect-ratio: 1;
      cursor: zoom-in;
    }

    .thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .more {
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      background: var(--ov-veil);
      color: var(--ov-on-media);
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    @media (min-width: 48rem) {
      .month {
        grid-template-columns: 11rem minmax(0, 1fr);
      }

      .month:not(.open) .month-head {
        grid-column: 1 / -1;
      }
    }
  `,
})
export class PropertyHistoryComponent {

  protected readonly store = inject(PropertyViewStore);
  private readonly locale = inject(LOCALE_ID);
  private readonly viewer = viewChild<PhotoViewerComponent>('viewer');

  protected readonly maxThumbs = MAX_THUMBS;
  protected readonly heading = monthHeading;

  protected readonly filters: { value: 'ALL' | 'HOUSE' | 'COMMON'; label: string }[] = [
    { value: 'ALL', label: 'Todo' },
    { value: 'HOUSE', label: 'Tu casa' },
    { value: 'COMMON', label: 'El barrio' },
  ];
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  /** Lo elegido vive en la dirección (`?ver=casa|barrio`): abrir un recorrido y volver lo conserva. */
  protected readonly filter = signal<'ALL' | 'HOUSE' | 'COMMON'>(filterFromQuery(this.route.snapshot.queryParamMap.get('ver')));

  protected chooseFilter(value: 'ALL' | 'HOUSE' | 'COMMON'): void {
    this.filter.set(value);
    replaceQuery(this.router, this.route, { ver: value === 'HOUSE' ? 'casa' : value === 'COMMON' ? 'barrio' : null });
  }

  protected readonly hasCommon = computed(() => (this.store.view()?.timeline ?? []).some(entry => entry.common));

  /** Avances y recorridos del filtro elegido, agrupados por mes. */
  protected readonly months = computed(() => {
    const view = this.store.view();
    if (!view) {
      return [];
    }
    const filter = this.filter();
    const keep = (common: boolean) => filter === 'ALL' || common === (filter === 'COMMON');
    return historyMonths(
      view.timeline.filter(entry => keep(entry.common)),
      view.captures.filter(capture => keep(capture.common)),
      view.sectors,
    );
  });

  /** Lo que el usuario abrió o cerró a mano; el resto sigue la regla de los dos más recientes. */
  private readonly toggled = signal<ReadonlyMap<string, boolean>>(new Map());
  protected readonly viewerPhotos = signal<ViewerPhoto[]>([]);

  protected isOpen(month: string, index: number): boolean {
    return this.toggled().get(month) ?? index < OPEN_BY_DEFAULT;
  }

  protected toggle(month: string, index: number): void {
    const next = !this.isOpen(month, index);
    this.toggled.update(state => new Map(state).set(month, next));
  }

  protected countOf(entries: number): string {
    if (entries === 0) {
      return 'solo recorridos 3D';
    }
    return entries === 1 ? '1 avance' : `${entries} avances`;
  }

  /** Abre el visor con las fotos de ese avance, en la que se tocó. */
  protected openPhotos(entry: PublicProgressEntry, index: number): void {
    // La carga inicial tiene fechas aproximadas: el mes, no el día.
    const caption = formatDate(entry.date, entry.source === 'INITIAL_LOAD' ? 'MMMM y' : 'longDate', this.locale);
    this.viewerPhotos.set(entry.photos.map(photo => ({ url: variantOf(photo, 'screen')!, caption })));
    this.viewer()?.open(index);
  }
}
