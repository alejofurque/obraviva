import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { PublicPlace, PublicPlaceMonth } from '../../core/api/api.models';
import { placeTour } from './places-derivations';
import { longMonth, monthAndYear } from './property-derivations';

/**
 * El 3D de un lugar en un mes, como tarjeta: oscura, con la primera foto del lugar ese mes
 * de fondo y velada, y el botón blanco "Recorrer en 3D". Sin 3D ese mes, un bloque con borde
 * punteado que lo dice y ofrece el anterior, o que el lugar se sigue con fotos y videos.
 */
@Component({
  selector: 'app-place-tour-card',
  imports: [RouterLink, MatIconModule, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @switch (tour().kind) {
      @case ('tour') {
        <a class="tour" [routerLink]="tourPath()" queryParamsHandling="merge"
           [queryParams]="{ mes: month().month, sector: place().name, lugar: null }">
          @if (month().photos[0]; as cover) {
            <img class="tour-bg" [src]="cover | variant: 'thumb'" alt="" loading="lazy" (error)="failed.emit(cover.url)" />
          }
          <span class="tour-tag">Recorrido 3D de {{ monthAndYear(month().month) }}</span>
          <span class="tour-go"><mat-icon svgIcon="view_in_ar" aria-hidden="true" />Recorrer en 3D</span>
        </a>
      }
      @default {
        <div class="no-tour">
          <p class="no-tour-title">Este mes no hubo recorrido 3D</p>
          @if (tour().kind === 'photos-only') {
            <p class="muted">Este lugar se sigue con fotos y videos.</p>
          } @else if (previous(); as earlier) {
            <p class="muted">El último es de {{ longMonth(earlier) }}.</p>
            <a class="outline" [routerLink]="tourPath()" queryParamsHandling="merge"
               [queryParams]="{ mes: earlier, sector: place().name, lugar: null }">Ver el de {{ monthAndYear(earlier) }}</a>
          }
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .tour,
    .no-tour {
      position: relative;
      height: 16.25rem;
      overflow: hidden;
      border-radius: var(--ov-radius-md);
    }

    .tour {
      display: block;
      background: radial-gradient(circle at 30% 30%, var(--ov-ink-hover), var(--ov-ink));
      color: var(--ov-on-media);
      text-decoration: none;
    }

    .tour::after {
      content: '';
      position: absolute;
      inset: 0;
      background: var(--ov-veil);
    }

    .tour-bg {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .tour-tag,
    .tour-go {
      position: absolute;
      z-index: 1;
      left: 1.25rem;
      border-radius: var(--ov-radius-pill);
      font-weight: var(--ov-weight-semibold);
    }

    .tour-tag {
      top: 1.125rem;
      padding: var(--ov-space-1) 0.625rem;
      background: var(--ov-glass);
      font-size: var(--ov-text-xs);
    }

    .tour-go {
      bottom: 1.25rem;
      display: inline-flex;
      align-items: center;
      gap: 0.625rem;
      min-height: 3rem;
      padding: 0 1.25rem;
      background: var(--ov-on-media);
      color: var(--ov-ink);
    }

    .tour:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 3px;
    }

    .no-tour {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.625rem;
      padding: var(--ov-space-5);
      border: 1px dashed var(--ov-line-strong);
      background: var(--ov-canvas);
      text-align: center;
    }

    .no-tour-title {
      font-weight: var(--ov-weight-semibold);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .outline {
      display: inline-flex;
      align-items: center;
      min-height: 2.75rem;
      padding: 0 1.125rem;
      border: 1px solid var(--ov-ink);
      border-radius: var(--ov-radius-pill);
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
      text-decoration: none;
    }
  `,
})
export class PlaceTourCardComponent {

  readonly place = input.required<PublicPlace>();
  readonly month = input.required<PublicPlaceMonth>();
  /** La ruta del recorrido 3D; el mes y el lugar van como parámetros. */
  readonly tourPath = input.required<string[]>();
  readonly failed = output<string>();

  protected readonly longMonth = longMonth;
  protected readonly monthAndYear = monthAndYear;

  protected readonly tour = computed(() => placeTour(this.place(), this.month().month));
  protected readonly previous = computed(() => {
    const tour = this.tour();
    return tour.kind === 'missed' ? tour.previous : null;
  });
}
