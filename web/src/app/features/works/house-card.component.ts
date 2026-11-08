import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HouseCard, Unit } from '../../core/api/api.models';
import { cardStageLine, houseActivityLine } from './overview-derivations';
import { SALES_STATUS_LABELS, formatPrice } from './work-labels';
import { ImagePipe } from '../../shared/media/variant.pipe';

/** Dónde está la obra de una casa, en palabras: sin empezar, en qué etapa o terminada. */
export function houseStageLine(unit: Pick<Unit, 'progressPercent' | 'currentStageName' | 'stageCount'>): string {
  if (!unit.stageCount) {
    return 'Sin etapas cargadas';
  }
  const percent = unit.progressPercent ?? 0;
  if (percent >= 100) {
    return 'Obra terminada';
  }
  if (percent === 0) {
    return 'Sin empezar';
  }
  return unit.currentStageName ?? 'En obra';
}

/**
 * Una casa en la grilla del desarrollo: portada, avance y, para el dueño, cómo va su
 * venta. En el Resumen (con `card`) dice además la etapa en curso con "+N", lo último que
 * pasó con su punto de sin ver y, para el dueño, la alerta de gasto. Toda la tarjeta lleva a
 * la casa.
 */
@Component({
  selector: 'app-house-card',
  imports: [ImagePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let house = unit();
    @let summary = card();
    <a class="ov-card card" [routerLink]="['/obras', house.developmentId, 'casas', house.id]">
      <div class="media">
        @if (house.coverUrl) {
          <img [src]="house | image: 'cover' : 'thumb'" [alt]="'Portada de ' + house.code" loading="lazy" />
        } @else {
          <span class="placeholder" aria-hidden="true">{{ initials() }}</span>
        }
        @if (owner() && salesLabel()) {
          <span class="ov-pill status" [attr.data-status]="house.salesStatus">{{ salesLabel() }}</span>
        }
        @if (summary?.alertLight) {
          <span class="alert" [attr.title]="summary?.alertStageName ? 'En alerta: ' + summary?.alertStageName : null">Alerta de gasto</span>
        }
      </div>
      <div class="body">
        <div class="name-row">
          <p class="name">{{ house.code }}</p>
          <p class="percent ov-num">{{ house.progressPercent ?? 0 }}%</p>
        </div>
        @if (!summary) {
          <p class="muted">{{ house.modelName ?? 'Sin modelo' }}@if (house.coveredAreaM2 ?? house.areaM2; as area) { · {{ area }} m² }@if (house.bedrooms !== null) { · {{ house.bedrooms }} dorm. }</p>
        }
        <div class="ov-progress" [class.alerted]="!!summary?.alertLight" aria-hidden="true"><span [style.width.%]="house.progressPercent ?? 0"></span></div>
        <p class="stage">{{ stageLine() }}</p>
        @if (summary) {
          <p class="activity">
            <span class="dot" [class.unseen]="!!summary.unseen" [attr.aria-label]="summary.unseen ? 'Sin ver' : null"></span>
            <span>{{ activity() }}</span>
          </p>
        } @else if (owner()) {
          <p class="commercial">
            @if (price(); as value) { <span class="ov-num">{{ value }}</span> }
            <span class="muted">{{ (house.buyerCount ?? 0) > 0 ? 'Con comprador' : 'Sin comprador' }}</span>
          </p>
        }
      </div>
    </a>
  `,
  styles: `
    .card {
      display: grid;
      /* Sin minmax, la columna toma el ancho propio de la imagen y el cuerpo se corta. */
      grid-template-columns: minmax(0, 1fr);
      /* Si la fila es más alta que la tarjeta, el espacio va al cuerpo, no a la imagen. */
      grid-template-rows: auto 1fr;
      overflow: hidden;
      height: 100%;
      color: inherit;
      text-decoration: none;
      transition: border-color var(--ov-duration-fast) var(--ov-ease), box-shadow var(--ov-duration-fast) var(--ov-ease);
    }

    .card:hover {
      border-color: var(--ov-line-strong);
      box-shadow: var(--ov-shadow-overlay);
    }

    .card:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .media {
      position: relative;
      aspect-ratio: 16 / 10;
      background: var(--ov-surface-muted);
    }

    .media img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .placeholder {
      display: grid;
      place-items: center;
      height: 100%;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
    }

    .status {
      position: absolute;
      top: var(--ov-space-3);
      left: var(--ov-space-3);
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .status[data-status='AVAILABLE'] {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .body {
      display: grid;
      gap: var(--ov-space-2);
      padding: var(--ov-space-4) var(--ov-space-5) var(--ov-space-5);
    }

    .name-row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .name {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .percent {
      font-weight: var(--ov-weight-semibold);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .stage {
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .status[data-status='SOLD'] {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .alert {
      position: absolute;
      top: var(--ov-space-3);
      right: var(--ov-space-3);
      padding: 0.25rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-danger-soft);
      color: var(--ov-danger);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .ov-progress.alerted > span {
      background: var(--ov-danger);
    }

    .activity {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      padding-top: var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .dot {
      flex: none;
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--ov-line-strong);
    }

    .dot.unseen {
      background: var(--ov-accent);
    }

    .commercial {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--ov-space-2);
      padding-top: var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class HouseCardComponent {

  readonly unit = input.required<Unit>();
  /** El dueño ve estado comercial, precio y comprador; el encargado, no. */
  readonly owner = input(false);
  /** En el Resumen del desarrollo: la etapa con "+N", lo último que pasó y la alerta de gasto. */
  readonly card = input<HouseCard | null>(null);

  protected readonly salesLabel = computed(() => {
    const status = this.unit().salesStatus;
    return status ? SALES_STATUS_LABELS[status] : '';
  });
  protected readonly stageLine = computed(() => {
    const card = this.card();
    return card ? cardStageLine(this.unit(), card) : houseStageLine(this.unit());
  });
  protected readonly activity = computed(() => houseActivityLine(this.card()));
  protected readonly price = computed(() => formatPrice(this.unit().listPrice));

  /** "Casa 12" a "12", "Lote A" a "LA": algo que se lea en la tarjeta sin portada. */
  protected readonly initials = computed(() => {
    const code = this.unit().code.trim();
    const number = code.match(/\d+$/);
    return number ? number[0] : code.split(/\s+/).map(word => word[0]).join('').slice(0, 2).toUpperCase();
  });
}
