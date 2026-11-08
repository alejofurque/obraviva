import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PublicStage } from '../../../core/api/api.models';
import { stageCells } from '../buyer-derivations';

/**
 * "El camino de tu casa" en una grilla compacta: cada etapa con su número, terminada, en curso
 * o por venir. Una demorada se ve en curso, sin alarma: el motivo está en la entrega. El
 * detalle, con lo que incluye cada una y sus fechas, en su propia pantalla.
 */
@Component({
  selector: 'app-mi-casa-journey',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="journey" aria-labelledby="journey-title">
      <div class="intro">
        <h2 id="journey-title" class="ov-display heading">El camino de tu casa</h2>
        <p class="lead">{{ cells().length }} etapas, de los cimientos a la entrega de llaves. Cada una con lo que incluye y sus fechas.</p>
        <a class="ov-link strong" [routerLink]="detailLink()" queryParamsHandling="preserve">Ver todas las etapas</a>
      </div>
      <ol class="grid">
        @for (cell of cells(); track cell.number) {
          <li class="cell" [attr.data-state]="cell.state">
            <span class="number ov-num">{{ cell.number }}</span>
            <span class="name">{{ cell.name }}</span>
            <span class="state">{{ cell.label }}</span>
          </li>
        }
      </ol>
    </section>
  `,
  styles: `
    .journey {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-8);
    }

    .intro {
      display: grid;
      flex: 1 1 18rem;
      align-content: start;
      gap: var(--ov-space-3);
    }

    .heading {
      font-size: var(--ov-text-2xl);
    }

    .lead {
      color: var(--ov-ink-muted);
      line-height: 1.6;
    }

    .grid {
      display: grid;
      flex: 2 1 32rem;
      grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .cell {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-3) var(--ov-space-4);
      border: 1px dashed var(--ov-line-strong);
      border-radius: var(--ov-radius-sm);
      color: var(--ov-ink-muted);
    }

    .cell[data-state='DONE'] {
      border: 1px solid var(--ov-line);
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .cell[data-state='NOW'] {
      border: 1px solid var(--ov-accent);
      background: var(--ov-accent-soft);
      color: var(--ov-ink);
    }

    .number,
    .state {
      font-size: var(--ov-text-xs);
    }

    .cell[data-state='NOW'] .number,
    .cell[data-state='NOW'] .state {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .name {
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      line-height: 1.3;
    }

    @media (max-width: 40rem) {
      .heading {
        font-size: var(--ov-text-xl);
      }

      .grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class MiCasaJourneyComponent {
  readonly stages = input.required<PublicStage[]>();
  readonly detailLink = input.required<string[]>();

  protected readonly cells = computed(() => stageCells(this.stages()));
}
