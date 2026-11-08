import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { UnitDashboardRow } from '../../core/api/api.models';
import { MoneyPipe } from '../../shared/money/money.pipe';
import { formatWholeMoney } from '../../shared/money/money';
import { partEstimate, partState, shortDifference } from './economics-derivations';

const COMMON_KEY = 'comunes';

/**
 * "Por casa": cada casa y, al final, las obras comunes,
 * con su presupuesto, lo gastado contra lo avanzado, el costo final estimado con su diferencia y
 * el estado de su peor etapa. Tocar una casa lleva a su Económico; el nombre es además un enlace,
 * para el teclado y el lector de pantalla. Tocar las obras comunes baja a sus etapas.
 */
@Component({
  selector: 'app-development-budget-table',
  imports: [MoneyPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <table class="house-table">
        <thead>
          <tr>
            <th scope="col">Casa</th>
            <th scope="col" class="num">Presupuesto</th>
            <th scope="col" class="num">Gastado</th>
            <th scope="col" class="versus">Gasto vs. avance</th>
            <th scope="col" class="num">Costo final estimado</th>
            <th scope="col">Estado</th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.key) {
            <tr [attr.data-state]="row.state.state" [class.common]="row.house === null" (click)="open(row.house, $event)">
              <th scope="row" class="name">
                @if (row.house; as house) {
                  <a [routerLink]="['/obras', developmentId(), 'casas', house.unitId, 'economico']">{{ house.code }}</a>
                } @else {
                  <button type="button" class="ov-link" (click)="commons.emit()">Obras comunes</button>
                }
              </th>
              <td class="num budget">{{ row.data.budgetAmount | money: 'whole' }}</td>
              <td class="num spent">{{ row.data.spentAmount === 0 ? '—' : (row.data.spentAmount | money: 'whole') }}</td>
              <td class="versus">
                @if (row.data.budgetUsedPercent !== null && row.data.stagesWithoutBudget === 0) {
                  <div class="bar" role="img" [attr.aria-label]="row.used + '% gastado, ' + row.data.progressPercent + '% de avance'">
                    <span class="spent-bar" [style.width.%]="row.fill"></span>
                    <span class="mark" [style.left.%]="row.data.progressPercent"></span>
                  </div>
                  <p class="bar-text" aria-hidden="true"><span>{{ row.used }}% gastado</span><span>{{ row.data.progressPercent }}% avance</span></p>
                } @else {
                  <p class="bar-text">{{ row.data.progressPercent }}% avance</p>
                }
              </td>
              <td class="num estimate">
                <span class="value">{{ row.estimate }}</span>
                @if (row.difference; as difference) {
                  <span class="difference" [class.warn]="row.over">{{ difference }}</span>
                }
              </td>
              <td class="state">
                <span class="chip">{{ row.state.label }}</span>
                @if (row.state.detail; as detail) {
                  <span class="detail">{{ detail }}</span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host { display: block; }
    .wrap { overflow-x: auto; }

    .house-table {
      width: 100%;
      min-width: 50rem;
      border-collapse: collapse;
      font-size: var(--ov-text-sm);
    }

    th, td {
      padding: var(--ov-space-3) var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      text-align: left;
      vertical-align: middle;
    }

    th:first-child { padding-left: var(--ov-space-5); }
    td:last-child { padding-right: var(--ov-space-5); }

    thead th {
      border-top: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    tbody tr { cursor: pointer; }
    tbody tr:hover { background: var(--ov-surface-muted); }
    tr.common th, tr.common td { border-top: 2px solid var(--ov-line-strong); }

    .name a, .name .ov-link {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
      text-decoration: none;
    }

    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    .versus { width: 12rem; }

    .bar {
      position: relative;
      height: 0.625rem;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .spent-bar {
      position: absolute;
      inset: 0 auto 0 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent);
    }

    tr[data-state='watch'] .spent-bar, tr[data-state='over'] .spent-bar { background: var(--ov-status-delayed); }

    .mark {
      position: absolute;
      top: -0.1875rem;
      width: 2px;
      height: 1rem;
      margin-left: -1px;
      background: var(--ov-ink);
    }

    .bar-text {
      display: flex;
      justify-content: space-between;
      margin-top: var(--ov-space-1);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .estimate .value { display: block; font-weight: var(--ov-weight-medium); }
    .difference { color: var(--ov-ink-muted); font-size: var(--ov-text-xs); }
    .difference.warn { color: var(--ov-status-delayed); font-weight: var(--ov-weight-semibold); }

    .chip {
      padding: 0.2rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      white-space: nowrap;
    }

    tr[data-state='ok'] .chip { background: var(--ov-accent-soft); color: var(--ov-accent); }
    tr[data-state='watch'] .chip, tr[data-state='over'] .chip { background: var(--ov-status-delayed-soft); color: var(--ov-status-delayed); }
    .detail { display: block; margin-top: var(--ov-space-1); color: var(--ov-ink-muted); font-size: var(--ov-text-xs); }

    /* Teléfono: cada casa en un bloque, sin scroll horizontal. */
    @media (max-width: 40rem) {
      .house-table { min-width: 0; }
      thead { display: none; }

      tr {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas: 'name state' 'budget budget' 'spent spent' 'versus versus' 'estimate estimate';
        gap: var(--ov-space-2) var(--ov-space-3);
        padding: var(--ov-space-3) var(--ov-space-5);
        border-top: 1px solid var(--ov-line);
      }

      tr.common { border-top: 2px solid var(--ov-line-strong); }
      th, td, th:first-child, td:last-child, tr.common th, tr.common td { padding: 0; border: 0; }
      .name { grid-area: name; }
      .state { grid-area: state; text-align: right; }
      .budget { grid-area: budget; text-align: left; }
      .spent { grid-area: spent; text-align: left; }
      .versus { grid-area: versus; width: auto; }
      .estimate { grid-area: estimate; display: flex; flex-wrap: wrap; justify-content: space-between; gap: 0 var(--ov-space-2); text-align: left; }
      .estimate .value { display: inline; }
      .budget::before { content: 'Presupuesto '; color: var(--ov-ink-muted); }
      .spent::before { content: 'Gastado '; color: var(--ov-ink-muted); }
      .estimate .value::before { content: 'Costo final estimado '; color: var(--ov-ink-muted); font-weight: var(--ov-weight-regular); }
    }
  `,
})
export class DevelopmentBudgetTableComponent {
  private readonly router = inject(Router);

  readonly developmentId = input.required<string>();
  readonly houses = input.required<UnitDashboardRow[]>();
  readonly commonWorks = input<UnitDashboardRow | null>(null);
  /** Tocaron las obras comunes: la página baja a sus etapas. */
  readonly commons = output<void>();

  protected readonly rows = computed(() => {
    const common = this.commonWorks();
    return [...this.houses().map(house => ({ data: house, house })), ...(common ? [{ data: common, house: null }] : [])]
      .map(({ data, house }) => {
        const estimate = partEstimate(data);
        const used = data.budgetUsedPercent === null ? 0 : Math.round(data.budgetUsedPercent);
        return {
          key: house?.unitId ?? COMMON_KEY,
          data,
          house,
          used,
          fill: Math.min(data.budgetUsedPercent ?? 0, 100),
          estimate: estimate.value === null ? '—' : formatWholeMoney(estimate.value),
          difference: shortDifference(data, estimate),
          over: Math.round(estimate.differencePercent ?? 0) > 0,
          state: partState(data),
        };
      });
  });

  /** Toda la fila responde; el enlace o el botón del nombre se manejan solos. */
  protected open(house: UnitDashboardRow | null, event: MouseEvent): void {
    if ((event.target as HTMLElement).closest('a, button')) return;
    if (house) {
      void this.router.navigate(['/obras', this.developmentId(), 'casas', house.unitId, 'economico']);
    } else {
      this.commons.emit();
    }
  }
}
