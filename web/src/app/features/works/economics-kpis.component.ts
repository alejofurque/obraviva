import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { EconomicsKpi } from './economics-derivations';

/** La fila de cuatro indicadores del Económico, de a dos en el teléfono. */
@Component({
  selector: 'app-economics-kpis',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="kpis" aria-label="Indicadores">
      @for (kpi of kpis(); track kpi.label) {
        <div class="ov-card kpi">
          <p class="ov-eyebrow">{{ kpi.label }}</p>
          <p class="value ov-num" [class.warn]="kpi.warn">{{ kpi.value }}</p>
          @if (kpi.bar; as bar) {
            <div class="bar" role="img" [attr.aria-label]="bar.label">
              <span class="fill" [class.warn]="bar.warn" [style.width.%]="bar.fill"></span>
              @if (bar.mark !== null) {
                <span class="mark" [style.left.%]="bar.mark"></span>
              }
            </div>
          }
          @for (line of kpi.lines; track $index) {
            <p class="line" [class.warn]="kpi.warn && $index === 0">{{ line }}</p>
          }
          @if (kpi.note; as note) {
            <p class="note">{{ note }}</p>
          }
        </div>
      }
    </section>
  `,
  styles: `
    .kpis {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--ov-space-3);
    }

    .kpi {
      display: grid;
      align-content: start;
      gap: var(--ov-space-2);
      min-width: 0;
      padding: var(--ov-space-4);
      container-type: inline-size;
    }

    /* El número entra entero en su tarjeta: hasta US$ 1.250.000 en el teléfono, sin cortarse. */
    .value {
      font-size: min(var(--ov-text-xl), 13cqi);
      font-weight: var(--ov-weight-semibold);
      line-height: 1.1;
      white-space: nowrap;
    }

    .warn {
      color: var(--ov-status-delayed);
    }

    .line {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .line.warn {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-medium);
    }

    .note {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .bar {
      position: relative;
      height: 0.5rem;
      margin: var(--ov-space-1) 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .fill {
      position: absolute;
      inset: 0 auto 0 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent);
    }

    .fill.warn {
      background: var(--ov-status-delayed);
    }

    .mark {
      position: absolute;
      top: -0.1875rem;
      width: 2px;
      height: 0.875rem;
      margin-left: -1px;
      background: var(--ov-ink);
    }

    @media (min-width: 64rem) {
      .kpis {
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: var(--ov-space-4);
      }

      .kpi {
        padding: var(--ov-space-5);
      }
    }
  `,
})
export class EconomicsKpisComponent {
  readonly kpis = input.required<EconomicsKpi[]>();
}
