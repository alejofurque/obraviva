import { formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy, Component, ElementRef, LOCALE_ID, afterRenderEffect, computed, inject, input, output, signal, viewChild,
} from '@angular/core';
import { PublicPlace, PublicPlaceMonth } from '../../core/api/api.models';
import { VariantPipe, variantOf } from '../../shared/media/variant.pipe';
import { PlaceTourCardComponent } from './place-tour-card.component';
import { mediaCount, mediaDay, monthsChronological, placesWithMaterial } from './places-derivations';
import { PhotoViewerComponent, ViewerPhoto } from './photo-viewer.component';
import { longMonth, monthAndYear, monthName, monthStop } from './property-derivations';

/**
 * "Tu casa, lugar por lugar": chips
 * de lugar y, en una tarjeta, una línea de tiempo con un punto por mes que arranca en el
 * último. Debajo, dos columnas: el 3D del mes como tarjeta (o por qué no hay) con el texto
 * de los avances, y las fotos y videos del mes. En el teléfono, una columna, 3D primero.
 */
@Component({
  selector: 'app-property-places',
  imports: [PlaceTourCardComponent, PhotoViewerComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (place(); as current) {
      <section class="places" aria-labelledby="places-title">
        <div class="head">
          <h2 id="places-title">Tu casa, lugar por lugar</h2>
          <p class="lead">Elegí un lugar y mirá cómo avanzó mes a mes, en fotos, videos y en 3D.</p>
        </div>

        @if (visible().length > 1) {
          <div class="chips" role="radiogroup" aria-label="Lugares">
            @for (item of visible(); track item.name) {
              <button type="button" role="radio" class="chip ov-hit" [class.active]="item.name === current.name"
                      [attr.aria-checked]="item.name === current.name" (click)="choosePlace(item.name)">{{ item.name }}</button>
            }
          </div>
        }

        @if (month(); as shown) {
          <div class="card">
            <div class="when">
              <p class="muted">Meses con material de {{ current.name }}</p>
              <div #timeline class="timeline" role="radiogroup" aria-label="Meses">
                @for (item of months(); track item.month; let i = $index) {
                  <button type="button" role="radio" class="stop" [class.past]="i <= monthIndex()"
                          [class.active]="item.month === shown.month" [attr.aria-checked]="item.month === shown.month"
                          [attr.aria-label]="longMonth(item.month)" (click)="chosenMonth.set(item.month)">
                    <span class="dot" aria-hidden="true"></span>
                    <span class="label">{{ monthStop(item.month) }}</span>
                  </button>
                }
              </div>
            </div>

            <div class="columns">
              <div class="side">
                <app-place-tour-card [place]="current" [month]="shown" [tourPath]="tourPath()" (failed)="failed.emit($event)" />
                @for (note of shown.notes; track $index) {
                  <p class="note"><strong>{{ monthName(shown.month) }} · </strong>{{ note.description }}</p>
                }
              </div>

              <div class="media">
                <div class="media-head">
                  <h3>Fotos y videos de {{ monthAndYear(shown.month) }}</h3>
                  <p class="muted">{{ count() }}</p>
                </div>
                @if (shown.photos.length + shown.videos.length > 0) {
                  <ul class="grid">
                    @for (video of shown.videos; track video.url) {
                      <li>
                        <!-- metadata: el navegador lee la duración sin bajar el video entero. -->
                        <video class="tile" [src]="video.url" [poster]="video | variant: 'poster'" controls preload="metadata" playsinline
                               [attr.aria-label]="'Video de ' + current.name" (error)="failed.emit(video.url)"></video>
                      </li>
                    }
                    @for (photo of shown.photos; track photo.url; let i = $index) {
                      <li>
                        <button type="button" class="tile shot" (click)="viewer.open(i)" [attr.aria-label]="'Ampliar foto ' + (i + 1)">
                          <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" (error)="failed.emit(photo.url)" />
                        </button>
                      </li>
                    }
                  </ul>
                } @else {
                  <p class="muted">Este mes hay recorrido 3D, pero no fotos ni videos.</p>
                }
              </div>
            </div>
          </div>
        }
      </section>
      <app-photo-viewer #viewer [photos]="viewerPhotos()" [alt]="'Foto de ' + current.name + ' ampliada'"
                        (failed)="failed.emit($event)" />
    }
  `,
  styles: `
    .places {
      display: grid;
      gap: var(--ov-space-5);
    }

    .head {
      display: grid;
      gap: var(--ov-space-2);
    }

    h2 {
      font-size: var(--ov-text-xl);
      letter-spacing: -0.015em;
    }

    .lead,
    .muted {
      color: var(--ov-ink-muted);
    }

    .muted {
      font-size: var(--ov-text-sm);
    }

    /* Lugares: en el teléfono no se parten en líneas, se desplazan de costado. */
    .chips {
      display: flex;
      gap: var(--ov-space-2);
      padding-bottom: var(--ov-space-1);
      overflow-x: auto;
      scrollbar-width: none;
    }

    .chip {
      flex: none;
      min-height: 2.75rem;
      padding: 0 1.125rem;
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-weight: var(--ov-weight-medium);
      white-space: nowrap;
      cursor: pointer;
    }

    .chip.active {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .card {
      display: grid;
      gap: var(--ov-space-5);
      padding: var(--ov-space-5);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .when {
      display: grid;
      gap: var(--ov-space-3);
    }

    /* Un punto por mes sobre una línea; hasta el elegido, en el color de obra. */
    .timeline {
      position: relative;
      display: flex;
      padding-top: var(--ov-space-2);
      overflow-x: auto;
      scrollbar-width: none;
    }

    .stop {
      display: flex;
      flex: 1 0 5.5rem;
      flex-direction: column;
      align-items: center;
      gap: var(--ov-space-1);
      min-height: 3.5rem;
      padding: 0.625rem var(--ov-space-1) 0;
      border: 0;
      border-top: 3px solid var(--ov-line);
      background: none;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .stop.past {
      border-top-color: var(--ov-accent);
    }

    .dot {
      width: 0.625rem;
      height: 0.625rem;
      margin-top: -0.625rem;
      border: 2px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
    }

    .past .dot {
      border-color: var(--ov-accent);
      background: var(--ov-accent);
    }

    .active .dot {
      width: 1rem;
      height: 1rem;
      margin-top: -0.8125rem;
      box-shadow: 0 0 0 4px var(--ov-accent-soft);
    }

    .stop.active {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .stop:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-5);
    }

    .side {
      display: grid;
      flex: 1 1 21rem;
      gap: var(--ov-space-3);
      align-content: start;
    }

    .media {
      display: grid;
      flex: 2 1 26rem;
      gap: var(--ov-space-3);
      align-content: start;
    }

    h3 {
      font-size: var(--ov-text-md);
      font-weight: var(--ov-weight-semibold);
    }

    .note {
      font-size: var(--ov-text-sm);
      line-height: var(--ov-leading-normal);
    }

    .media-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .media-head .muted {
      flex: none;
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9.375rem, 1fr));
      gap: 0.625rem;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .tile {
      display: block;
      width: 100%;
      aspect-ratio: 4 / 3;
      padding: 0;
      overflow: hidden;
      border: 0;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      object-fit: cover;
    }

    video.tile {
      background: var(--ov-ink);
    }

    .shot {
      cursor: zoom-in;
    }

    .shot img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .shot:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    /* Teléfono: menos relleno y fotos de a dos. */
    @media (max-width: 40rem) {
      .card {
        padding: var(--ov-space-4);
      }

      .grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class PropertyPlacesComponent {

  readonly places = input.required<PublicPlace[]>();
  /** La ruta del recorrido 3D; el mes y el lugar van como parámetros. */
  readonly tourPath = input.required<string[]>();
  /** El lugar con el que abre, por ejemplo desde una tarjeta de "Mi casa"; si no está, el primero. */
  readonly initialPlace = input<string | null>(null);
  /** Una foto o video que no cargó, por ejemplo por URL vencida. */
  readonly failed = output<string>();

  private readonly locale = inject(LOCALE_ID);
  private readonly timeline = viewChild<ElementRef<HTMLElement>>('timeline');
  private readonly chosenPlace = signal<string | null>(null);
  protected readonly chosenMonth = signal<string | null>(null);

  protected readonly monthStop = monthStop;
  protected readonly longMonth = longMonth;
  protected readonly monthAndYear = monthAndYear;
  protected readonly monthName = monthName;

  protected readonly visible = computed(() => placesWithMaterial(this.places()));

  /** Si la vista se renueva y el lugar elegido ya no está, se vuelve al primero. */
  protected readonly place = computed<PublicPlace | null>(() =>
    this.visible().find(place => place.name === (this.chosenPlace() ?? this.initialPlace())) ?? this.visible()[0] ?? null);

  protected readonly months = computed(() => {
    const place = this.place();
    return place ? monthsChronological(place) : [];
  });

  /** Sin elegir, el último mes: la línea arranca en lo más reciente. */
  protected readonly month = computed<PublicPlaceMonth | null>(() =>
    this.months().find(item => item.month === this.chosenMonth()) ?? this.months().at(-1) ?? null);

  protected readonly monthIndex = computed(() => {
    const month = this.month();
    return month ? this.months().indexOf(month) : -1;
  });

  protected readonly count = computed(() => {
    const month = this.month();
    return month ? mediaCount(month) : '';
  });

  protected readonly viewerPhotos = computed<ViewerPhoto[]>(() =>
    (this.month()?.photos ?? []).map(photo => ({ url: variantOf(photo, 'screen')!, caption: formatDate(mediaDay(photo), 'longDate', this.locale) })));

  constructor() {
    // Con muchos meses la línea se desplaza: el elegido queda a la vista, sin mover la página.
    afterRenderEffect(() => {
      this.month();
      const row = this.timeline()?.nativeElement;
      const active = row?.querySelector<HTMLElement>('.stop.active');
      if (row && active) {
        row.scrollLeft = active.offsetLeft - (row.clientWidth - active.clientWidth) / 2;
      }
    });
  }

  /** Al cambiar de lugar, la línea vuelve a su último mes. */
  protected choosePlace(name: string): void {
    this.chosenPlace.set(name);
    this.chosenMonth.set(null);
  }
}
