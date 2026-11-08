import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { PublicPlace } from '../../../core/api/api.models';
import { VariantPipe } from '../../../shared/media/variant.pipe';
import { placeCards } from '../buyer-derivations';

/**
 * "Recorré tu casa, lugar por lugar": cada lugar como tarjeta grande, con su foto más nueva,
 * si tiene 3D y de qué meses tiene material. Cada una abre el detalle mes a mes de siempre.
 */
@Component({
  selector: 'app-mi-casa-places',
  imports: [RouterLink, MatIconModule, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="places" aria-labelledby="places-title">
      <div class="head">
        <h2 id="places-title" class="ov-display heading">Recorré tu casa, lugar por lugar</h2>
        <p class="lead">Elegí un ambiente y mirá cómo cambió mes a mes, en fotos y en 3D.</p>
      </div>
      <ul class="grid">
        @for (card of cards(); track card.name) {
          <li>
            <a class="card" [routerLink]="detailLink()" [queryParams]="{ lugar: card.name }" queryParamsHandling="merge">
              <span class="thumb" [class.tour-only]="!card.photo">
                @if (card.photo; as photo) {
                  <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" (error)="failed.emit(photo.url)" />
                } @else {
                  <mat-icon class="tour-icon" svgIcon="view_in_ar" aria-hidden="true" />
                }
                @if (card.has3d) {
                  <span class="badge">3D</span>
                }
              </span>
              <span class="name-row"><span class="name">{{ card.name }}</span><span class="months">{{ card.months }}</span></span>
            </a>
          </li>
        }
      </ul>
    </section>
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

    .heading {
      font-size: var(--ov-text-2xl);
    }

    .lead {
      color: var(--ov-ink-muted);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(12.5rem, 1fr));
      gap: var(--ov-space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .card {
      display: grid;
      gap: var(--ov-space-2);
      color: inherit;
      text-decoration: none;
    }

    .card:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 3px;
      border-radius: var(--ov-radius-md);
    }

    .thumb {
      position: relative;
      display: block;
      overflow: hidden;
      aspect-ratio: 4 / 3;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    /* Sin fotos del lugar: una tarjeta clara con el ícono del 3D (no se generan imágenes del 3D). */
    .thumb.tour-only {
      display: grid;
      place-items: center;
      border: 1px solid var(--ov-line);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .tour-icon {
      width: 2.5rem;
      height: 2.5rem;
    }

    .thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform var(--ov-duration-fast) var(--ov-ease);
    }

    .card:hover .thumb img {
      transform: scale(1.03);
    }

    .badge {
      position: absolute;
      top: var(--ov-space-3);
      right: var(--ov-space-3);
      padding: 0.2rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .name-row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-2);
    }

    .name {
      font-weight: var(--ov-weight-semibold);
    }

    .months {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    @media (max-width: 40rem) {
      .heading {
        font-size: var(--ov-text-xl);
      }

      .grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: var(--ov-space-3);
      }
    }
  `,
})
export class MiCasaPlacesComponent {
  readonly places = input.required<PublicPlace[]>();
  /** La subruta del detalle mes a mes; el lugar va como ?lugar=. */
  readonly detailLink = input.required<string[]>();
  readonly failed = output<string>();

  protected readonly cards = computed(() => placeCards(this.places()));
}
