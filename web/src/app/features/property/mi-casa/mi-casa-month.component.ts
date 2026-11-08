import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VariantPipe, variantOf } from '../../../shared/media/variant.pipe';
import { todayLocal } from '../../../shared/time/local-date';
import { MonthStory, monthTitle, shortDay } from '../buyer-derivations';
import { PhotoViewerComponent } from '../photo-viewer.component';

/** Cuántas fotos entran en el mosaico antes de "+N fotos". */
const MOSAIC = 4;

/**
 * "Este mes en tu casa": los hechos del mes, contados por los avances que se publicaron (sin
 * resumen narrado: el reporte por casa es un paso aparte), y un mosaico con sus fotos que se
 * abre en el visor.
 */
@Component({
  selector: 'app-mi-casa-month',
  imports: [DatePipe, RouterLink, VariantPipe, PhotoViewerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = story();
    <section class="month" id="mes" aria-labelledby="month-title">
      <div class="text">
        <p class="eyebrow">{{ title() }}</p>
        <h2 id="month-title" class="ov-display heading">{{ current.month === thisMonth ? 'Este mes en tu casa' : 'Lo último en tu casa' }}</h2>
        <ul class="facts">
          @for (fact of current.facts; track $index) {
            <li>
              <span class="dot" aria-hidden="true"></span>
              <div>
                <p class="fact-title ov-num">{{ fact.title }}</p>
                <p class="fact-text">{{ fact.text }}</p>
                <p class="fact-when">{{ fact.date | date: "d 'de' MMMM" }}</p>
              </div>
            </li>
          }
        </ul>
        <a class="ov-link strong" [routerLink]="historyLink()" queryParamsHandling="preserve">Ver toda la historia</a>
      </div>

      @if (current.photos.length > 0) {
        <div class="mosaic" [attr.data-count]="shown().length">
          @for (photo of shown(); track photo.media.url; let index = $index) {
            <button type="button" class="tile" [class.big]="index === 0" (click)="viewer.open(index)"
                    [attr.aria-label]="'Ver foto' + (photo.sector ? ' de ' + photo.sector : '')">
              <img [src]="photo.media | variant: (index === 0 ? 'screen' : 'thumb')" alt="" loading="lazy"
                   (error)="failed.emit(photo.media.url)" />
              @if (index < 2 && (photo.sector || index === 0)) {
                <span class="tag">{{ photo.sector ? photo.sector + ' · ' : '' }}{{ day(photo.day) }}</span>
              }
            </button>
          }
          @if (rest() > 0) {
            <button type="button" class="tile more" (click)="viewer.open(shown().length)">+ {{ rest() }} fotos</button>
          }
        </div>
        <app-photo-viewer #viewer [photos]="viewerPhotos()" alt="Foto de la obra" (failed)="failed.emit($event)" />
      }
    </section>
  `,
  styles: `
    .month {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-8);
      scroll-margin-top: var(--ov-space-8);
    }

    /* El visor es un <dialog>: cerrado no ocupa lugar, y su elemento no debe sumar una fila. */
    app-photo-viewer {
      display: contents;
    }

    .text {
      display: grid;
      flex: 1 1 22rem;
      gap: var(--ov-space-4);
    }

    .eyebrow {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .heading {
      font-size: var(--ov-text-2xl);
    }

    .facts {
      display: grid;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .facts li {
      display: flex;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .dot {
      flex: none;
      width: 0.625rem;
      height: 0.625rem;
      margin-top: 0.4rem;
      border-radius: 50%;
      background: var(--ov-accent);
    }

    .fact-title {
      font-weight: var(--ov-weight-semibold);
    }

    .fact-text {
      font-size: var(--ov-text-sm);
      line-height: 1.5;
    }

    .fact-when {
      margin-top: var(--ov-space-1);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .mosaic {
      display: grid;
      flex: 1 1 26rem;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      grid-auto-rows: 8.75rem;
      gap: var(--ov-space-2);
    }

    .tile {
      position: relative;
      overflow: hidden;
      min-height: 2.75rem;
      padding: 0;
      border: 0;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      cursor: zoom-in;
    }

    .tile.big {
      grid-column: span 2;
      grid-row: span 2;
    }

    .mosaic[data-count='1'] .tile.big {
      grid-column: 1 / -1;
    }

    .tile img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .tag {
      position: absolute;
      bottom: var(--ov-space-2);
      left: var(--ov-space-3);
      color: var(--ov-on-media);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      text-shadow: 0 1px 3px var(--ov-ink);
    }

    .more {
      background: var(--ov-ink);
      color: var(--ov-on-ink);
      font: inherit;
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    @media (max-width: 40rem) {
      .heading {
        font-size: var(--ov-text-xl);
      }

      .mosaic {
        grid-auto-rows: 6.5rem;
      }
    }
  `,
})
export class MiCasaMonthComponent {
  readonly story = input.required<MonthStory>();
  readonly historyLink = input.required<string[]>();
  readonly failed = output<string>();

  protected readonly title = computed(() => monthTitle(this.story().month));
  /** Si el último mes con avances no es éste, el título no dice "este mes". */
  protected readonly thisMonth = todayLocal().slice(0, 7);
  protected readonly shown = computed(() => this.story().photos.slice(0, MOSAIC));
  protected readonly rest = computed(() => this.story().photos.length - this.shown().length);
  protected readonly viewerPhotos = computed(() => this.story().photos.map(photo => ({
    url: variantOf(photo.media, 'screen') ?? photo.media.url,
    caption: (photo.sector ? photo.sector + ' · ' : '') + shortDay(photo.day),
  })));
  protected readonly day = shortDay;
}
