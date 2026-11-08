import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { SpendingPoint } from '../../core/api/api.models';
import { formatWholeMoney } from '../../shared/money/money';
import { monthlyBars } from './economics-derivations';

/**
 * "Gasto por mes": una barra por mes, hasta los últimos doce, con el mes en curso en gris porque
 * todavía no cerró; debajo, el promedio de los meses cerrados. Barras de CSS, sin librería y sin
 * montos encima, que con doce meses se pisan; el lector de pantalla recibe cada mes con su monto.
 */
@Component({
  selector: 'app-monthly-spending',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card monthly" aria-labelledby="monthly-title">
      <h3 id="monthly-title">Gasto por mes</h3>
      @if (chart().bars.length === 0) {
        <p class="muted">Todavía no hay gastos cargados.</p>
      } @else {
        <ol class="bars">
          @for (bar of chart().bars; track bar.month) {
            <li class="month" [class.current]="bar.current">
              <span class="ov-visually-hidden">{{ bar.label }}: {{ whole(bar.amount) }}{{ bar.current ? ', mes en curso' : '' }}</span>
              <span class="track" aria-hidden="true"><span class="bar" [style.height.%]="bar.height"></span></span>
              <span class="label" aria-hidden="true">{{ bar.label }}</span>
            </li>
          }
        </ol>
        @if (chart().average; as average) {
          <p class="muted">
            Promedio de {{ average.months === 1 ? 'el último mes cerrado' : 'los últimos ' + average.months + ' meses' }}:
            <strong>{{ whole(average.amount) }}</strong> por mes. El mes en curso va en gris.
          </p>
        }
      }
    </section>
  `,
  styles: `
    .monthly {
      display: grid;
      align-content: start;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .bars {
      display: flex;
      align-items: stretch;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .month {
      display: grid;
      flex: 1 1 0;
      grid-template-rows: 9rem auto;
      justify-items: center;
      gap: var(--ov-space-1);
      min-width: 0;
    }

    .track {
      display: flex;
      align-items: flex-end;
      justify-content: center;
      width: 100%;
    }

    .bar {
      width: 100%;
      max-width: 2.25rem;
      min-height: 1px;
      border-radius: var(--ov-radius-sm) var(--ov-radius-sm) 0 0;
      background: var(--ov-accent);
    }

    .current .bar {
      background: var(--ov-ink-muted);
    }

    .label {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .muted strong {
      color: var(--ov-ink);
    }

    @media (max-width: 40rem) {
      .bars {
        gap: var(--ov-space-1);
      }
    }
  `,
})
export class MonthlySpendingComponent {
  readonly points = input.required<SpendingPoint[]>();
  readonly currentMonth = input.required<string>();

  protected readonly chart = computed(() => monthlyBars(this.points(), this.currentMonth()));
  protected readonly whole = formatWholeMoney;
}
