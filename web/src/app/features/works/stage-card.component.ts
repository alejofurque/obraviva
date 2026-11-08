import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Stage } from '../../core/api/api.models';
import { ShortDatePipe } from '../../shared/time/short-date';
import { STAGE_STATE_LABELS, StageCard } from './stage-board';

/**
 * Una etapa en curso como tarjeta: número, nombre, estado, avance, desde cuándo, fin previsto y
 * su último avance. Es la de Etapas del dueño y la de "La casa" del encargado: las acciones
 * (y lo que cada uno agregue abajo) van proyectadas. La demora va en ámbar, nunca en rojo, y
 * siempre con la palabra que la explica.
 */
@Component({
  selector: 'app-stage-card',
  imports: [ShortDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = card();
    <article class="ov-card card" [class.late]="current.late">
      <div class="card-head">
        <div>
          <p class="num ov-num">{{ number(current.stage) }}</p>
          <h4 class="name">{{ current.stage.name }}</h4>
        </div>
        <span class="chip" [class.chip-late]="current.stage.status === 'DELAYED'">{{ labels[current.stage.status] }}</span>
      </div>
      <p class="pct"><span class="ov-num value">{{ current.stage.progressPercent }}%</span> de la etapa</p>
      <div class="bar" role="presentation"><span [style.width.%]="current.stage.progressPercent"></span></div>
      @if (current.stage.startedOn || current.stage.plannedEnd) {
        <p class="dates">
          @if (current.stage.startedOn) { <span>Desde el {{ current.stage.startedOn | shortDate: 'd MMM' }}</span> }
          @if (current.stage.plannedEnd) {
            <span [class.overdue]="current.overdue">Fin previsto {{ current.stage.plannedEnd | shortDate: 'd MMM' }}@if (current.overdue) { · pasó}</span>
          }
        </p>
      }
      @if (current.last; as last) {
        <p class="last"><span class="muted">Último avance · {{ last.entryDate | shortDate: 'd MMM' }}:</span> {{ last.description }}</p>
      }
      <ng-content />
    </article>
  `,
  styles: `
    :host {
      display: grid;
    }

    .card {
      display: grid;
      gap: var(--ov-space-3);
      align-content: start;
      padding: var(--ov-space-5);
    }

    .card.late {
      border-color: color-mix(in srgb, var(--ov-status-delayed) 40%, var(--ov-line));
    }

    .card-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .muted,
    .num {
      color: var(--ov-ink-muted);
      font-weight: var(--ov-weight-regular);
    }

    .num {
      font-size: var(--ov-text-xs);
    }

    .name {
      font-size: var(--ov-text-md);
      line-height: 1.3;
    }

    .chip {
      flex: none;
      padding: 0.2rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .chip-late {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .pct {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .value {
      margin-right: var(--ov-space-1);
      color: var(--ov-ink);
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
    }

    .bar {
      height: 0.5rem;
      overflow: hidden;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .bar span {
      display: block;
      height: 100%;
      background: var(--ov-accent);
    }

    .late .bar span {
      background: var(--ov-status-delayed);
    }

    .dates {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--ov-space-1) var(--ov-space-3);
      font-size: var(--ov-text-sm);
    }

    .overdue {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-semibold);
    }

    .last {
      padding-top: var(--ov-space-3);
      border-top: 1px solid var(--ov-line);
      font-size: var(--ov-text-sm);
      line-height: 1.45;
    }
  `,
})
export class StageCardComponent {
  readonly card = input.required<StageCard>();

  protected readonly labels = STAGE_STATE_LABELS;

  protected number(stage: Stage): string {
    return String(stage.orderIndex).padStart(2, '0');
  }
}
