import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { compactMoney, formatWholeMoney } from '../../shared/money/money';
import { CurveChart } from './economics-derivations';
import { SHORT_MONTHS } from '../../shared/time/short-date';

/** El área de dibujo en unidades del viewBox; se estira al tamaño de la caja. */
const SIZE = 1000;

/** Un tope para el eje, por encima del máximo, que se parte en tres marcas redondas (0, 60, 120, 180). */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(max));
  const step = [1.2, 1.5, 1.8, 2.4, 3, 4.5, 6, 9, 12].find(candidate => candidate * power >= max) ?? 12;
  return step * power;
}

/** "sep 25": un mes en el eje. */
function monthTick(month: string): string {
  return `${SHORT_MONTHS[Number(month.slice(5, 7)) - 1]} ${month.slice(2, 4)}`;
}

/**
 * "Gasto acumulado, mes a mes": lo previsto según las fechas y el presupuesto de cada etapa
 * (punteado) contra lo gastado (línea llena, hasta el mes actual). Sin librería de gráficos: las
 * líneas son un SVG que se estira a la caja con trazo de ancho fijo, y los rótulos de los ejes
 * son texto común ubicado en porcentaje, así se leen igual en el teléfono que en la pantalla
 * grande. El lector de pantalla recibe la lectura en palabras.
 */
@Component({
  selector: 'app-spending-curve',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card curve" aria-labelledby="curve-title">
      <div class="head">
        <h3 id="curve-title">Gasto acumulado, mes a mes</h3>
        <p class="legend" aria-hidden="true">
          @if (chart().planned) {
            <span><span class="key planned"></span>Previsto según las fechas de cada etapa</span>
          }
          <span><span class="key real"></span>Gastado</span>
        </p>
      </div>
      @if (chart().months.length === 0) {
        <p class="muted">Todavía no hay gastos ni etapas con fechas y presupuesto.</p>
      } @else {
        <div class="chart">
          <div class="y-axis" aria-hidden="true">
            @for (tick of yTicks(); track tick.top) {
              <span class="axis" [style.top.%]="tick.top">{{ tick.label }}</span>
            }
          </div>
          <div class="plot">
            <svg [attr.viewBox]="'0 0 ' + size + ' ' + size" preserveAspectRatio="none" role="img" [attr.aria-label]="summary()">
              @for (tick of yTicks(); track tick.top) {
                <line x1="0" [attr.x2]="size" [attr.y1]="tick.y" [attr.y2]="tick.y" class="grid" [class.base]="tick.value === 0"
                      vector-effect="non-scaling-stroke" />
              }
              @if (plannedPath(); as path) {
                <path [attr.d]="path" class="planned" vector-effect="non-scaling-stroke" />
              }
              @if (realPath(); as path) {
                <path [attr.d]="path" class="real" vector-effect="non-scaling-stroke" />
              }
              @if (lastPoint(); as point) {
                <path [attr.d]="'M' + point + ' l0 0'" class="dot" vector-effect="non-scaling-stroke" />
              }
            </svg>
            <div class="x-axis" aria-hidden="true">
              @for (tick of xTicks(); track tick.left) {
                <span class="axis" [class.first]="$first" [class.last]="$last && !$first" [class.minor]="tick.minor" [style.left.%]="tick.left">{{ tick.label }}</span>
              }
            </div>
          </div>
        </div>
      }
      @if (note(); as line) {
        <p class="muted">{{ line }}</p>
      }
    </section>
  `,
  styles: `
    .curve {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2) var(--ov-space-4);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .legend > span {
      display: inline-flex;
      align-items: center;
      gap: var(--ov-space-2);
    }

    .key {
      display: inline-block;
      width: 1.125rem;
    }

    .key.planned {
      border-top: 2px dashed var(--ov-ink-muted);
    }

    .key.real {
      height: 3px;
      border-radius: 2px;
      background: var(--ov-accent);
    }

    .chart {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: var(--ov-space-2);
      padding: var(--ov-space-2) 0;
    }

    .y-axis {
      position: relative;
      min-width: 3.25rem;
      height: 13rem;
    }

    .y-axis .axis {
      position: absolute;
      right: 0;
      transform: translateY(-50%);
    }

    .plot {
      min-width: 0;
    }

    svg {
      display: block;
      width: 100%;
      height: 13rem;
      overflow: visible;
    }

    .x-axis {
      position: relative;
      height: 1.25rem;
      margin-top: var(--ov-space-2);
    }

    .x-axis .axis {
      position: absolute;
      transform: translateX(-50%);
      white-space: nowrap;
    }

    .x-axis .first {
      transform: none;
    }

    .x-axis .last {
      transform: translateX(-100%);
    }

    .axis {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-variant-numeric: tabular-nums;
    }

    .grid {
      stroke: var(--ov-line);
      stroke-width: 1;
    }

    .grid.base {
      stroke: var(--ov-line-strong);
    }

    .planned {
      fill: none;
      stroke: var(--ov-ink-muted);
      stroke-width: 2;
      stroke-dasharray: 6 5;
    }

    .real {
      fill: none;
      stroke: var(--ov-accent);
      stroke-width: 3;
      stroke-linejoin: round;
    }

    .dot {
      stroke: var(--ov-accent);
      stroke-width: 10;
      stroke-linecap: round;
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    @media (max-width: 40rem) {
      .y-axis,
      svg {
        height: 10rem;
      }

      .y-axis {
        min-width: 2.5rem;
      }

      .x-axis .minor {
        display: none;
      }
    }
  `,
})
export class SpendingCurveComponent {
  readonly chart = input.required<CurveChart>();
  /** Lo que la curva prevista no incluye, o por qué no hay; nulo si no hay nada que aclarar. */
  readonly note = input<string | null>(null);

  protected readonly size = SIZE;
  private readonly top = computed(() => niceMax(this.chart().max));

  private x(index: number): number {
    const count = this.chart().months.length;
    return count <= 1 ? SIZE / 2 : SIZE * index / (count - 1);
  }

  private y(value: number): number {
    return SIZE - SIZE * value / this.top();
  }

  private path(values: (number | null)[]): string | null {
    const points = values
      .map((value, index) => (value === null ? null : `${this.x(index).toFixed(1)} ${this.y(value).toFixed(1)}`))
      .filter((point): point is string => point !== null);
    // Con un solo mes, un trazo de largo cero se ve como un punto.
    return points.length === 0 ? null : points.length === 1 ? `M${points[0]} l0 0` : 'M' + points.join(' L');
  }

  private readonly lastIndex = computed(() =>
    this.chart().real.reduce<number>((last, value, i) => (value === null ? last : i), -1));

  protected readonly plannedPath = computed(() => {
    const planned = this.chart().planned;
    return planned ? this.path(planned) : null;
  });
  protected readonly realPath = computed(() => this.path(this.chart().real));

  protected readonly lastPoint = computed(() => {
    const index = this.lastIndex();
    return index < 0 ? null : `${this.x(index).toFixed(1)} ${this.y(this.chart().real[index]!).toFixed(1)}`;
  });

  protected readonly yTicks = computed(() => [3, 2, 1, 0].map(step => {
    const value = this.top() * step / 3;
    const y = this.y(value);
    return { value, y, top: y / SIZE * 100, label: value === 0 ? '0' : compactMoney(value).replace(/^US\$\s/, '') };
  }));

  protected readonly xTicks = computed(() => {
    const months = this.chart().months;
    const count = Math.min(5, months.length);
    const indexes = count <= 1 ? [0]
      : [...new Set(Array.from({ length: count }, (_, k) => Math.round(k * (months.length - 1) / (count - 1))))];
    // Con cinco marcas, la segunda y la cuarta se ocultan en el teléfono para que no se pisen.
    return indexes.map((index, k) => ({
      left: this.x(index) / SIZE * 100, label: monthTick(months[index]), minor: indexes.length === 5 && k % 2 === 1,
    }));
  });

  protected readonly summary = computed(() => {
    const chart = this.chart();
    const index = this.lastIndex();
    const parts = [`Gastado acumulado: ${formatWholeMoney(index < 0 ? 0 : chart.real[index]!)}`];
    if (chart.planned) {
      if (index >= 0) parts.push(`previsto a la misma fecha: ${formatWholeMoney(chart.planned[index])}`);
      parts.push(`previsto al final: ${formatWholeMoney(chart.planned[chart.planned.length - 1])}`);
    }
    return parts.join('; ') + '.';
  });
}
