import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { JourneyState, JourneyStep } from './property-derivations';

/**
 * El camino de la casa: las etapas en orden, con lo terminado, lo que está en curso
 * (explicado, con su avance y la demora si la hay) y lo que viene, cada una con su fecha
 * real o prevista. Lo que incluye cada etapa se abre a pedido para no llenar la pantalla.
 */
@Component({
  selector: 'app-stage-journey',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="journey" aria-labelledby="journey-title">
      <div class="section-head">
        <h2 id="journey-title">El camino de tu casa</h2>
        <p class="lead">Las etapas en el orden en que se construye, con sus fechas reales y previstas.</p>
      </div>
      <ol class="steps">
        @for (step of steps(); track step.stage.orderIndex) {
          <li class="step" [attr.data-state]="step.state">
            <span class="marker" aria-hidden="true">{{ step.state === 'DONE' ? '✓' : '' }}</span>
            <div class="step-body">
              <div class="step-head">
                <p class="step-name">
                  {{ step.stage.name }}
                  <span class="ov-visually-hidden">: {{ stateWord(step.state) }}</span>
                </p>
                @if (step.when) {
                  <p class="step-when">{{ step.when }}</p>
                }
              </div>

              @if (step.state === 'CURRENT') {
                <div class="current">
                  @if (step.stage.description) {
                    <p>{{ step.stage.description }}</p>
                  }
                  <div class="current-bar">
                    <div class="ov-progress" aria-hidden="true"><span [style.width.%]="step.stage.progressPercent"></span></div>
                    <span class="ov-num">{{ step.stage.progressPercent }}% de esta etapa</span>
                  </div>
                  @if (step.late) {
                    <p class="late-note">Esta etapa viene más lenta de lo previsto. El equipo de obra lo cuenta en la historia.</p>
                  }
                </div>
              } @else if (step.stage.description) {
                <details class="ov-disclosure">
                  <summary>Qué incluye</summary>
                  <p>{{ step.stage.description }}</p>
                </details>
              }
            </div>
          </li>
        }
      </ol>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    h2 {
      font-size: var(--ov-text-xl);
      letter-spacing: -0.015em;
    }

    .section-head {
      display: grid;
      gap: var(--ov-space-2);
      margin-bottom: var(--ov-space-5);
    }

    .lead {
      max-width: var(--ov-reading-max);
      color: var(--ov-ink-muted);
    }

    .steps {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .step {
      position: relative;
      display: grid;
      grid-template-columns: 2rem minmax(0, 1fr);
      gap: var(--ov-space-4);
      padding-bottom: var(--ov-space-5);
    }

    .step::before {
      content: '';
      position: absolute;
      top: 1.75rem;
      bottom: 0;
      left: calc(1rem - 1px);
      width: 2px;
      background: var(--ov-line);
    }

    .step:last-child::before {
      display: none;
    }

    .step[data-state='DONE']::before {
      background: var(--ov-accent);
    }

    .marker {
      position: relative;
      display: grid;
      place-items: center;
      width: 2rem;
      height: 2rem;
      border: 2px solid var(--ov-line-strong);
      border-radius: 50%;
      background: var(--ov-canvas);
      color: var(--ov-on-ink);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .step[data-state='DONE'] .marker {
      border-color: var(--ov-accent);
      background: var(--ov-accent);
    }

    .step[data-state='CURRENT'] .marker {
      border-color: var(--ov-accent);
      box-shadow: inset 0 0 0 5px var(--ov-canvas);
      background: var(--ov-accent);
    }

    .step-body {
      display: grid;
      gap: var(--ov-space-2);
      padding-top: 0.2rem;
    }

    .step-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-1) var(--ov-space-4);
    }

    .step-name {
      font-weight: var(--ov-weight-medium);
    }

    .step[data-state='UPCOMING'] .step-name {
      color: var(--ov-ink-muted);
    }

    .step[data-state='CURRENT'] .step-name {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .step-when {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .current {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      border: 1px solid var(--ov-line);
    }

    .current p {
      max-width: var(--ov-reading-max);
      line-height: var(--ov-leading-normal);
    }

    .current-bar {
      display: grid;
      gap: var(--ov-space-2);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .late-note {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    details {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    summary {
      width: fit-content;
      cursor: pointer;
    }

    summary:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    details p {
      max-width: var(--ov-reading-max);
      margin-top: var(--ov-space-2);
      line-height: var(--ov-leading-normal);
    }
  `,
})
export class StageJourneyComponent {

  readonly steps = input.required<JourneyStep[]>();

  protected stateWord(state: JourneyState): string {
    return state === 'DONE' ? 'terminada' : state === 'CURRENT' ? 'en curso' : 'por hacer';
  }
}
