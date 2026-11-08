import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Stage } from '../../core/api/api.models';
import { STAGE_STATE_LABELS, isOverdue } from './stage-board';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * "Lista": todas las etapas en una tabla, con estado, avance, inicio (el real o el previsto),
 * fin previsto (en ámbar si pasó) y peso. En el teléfono la tabla se corre de costado.
 */
@Component({
  selector: 'app-stage-table',
  imports: [ShortDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-card wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">#</th>
            <th scope="col">Etapa</th>
            <th scope="col">Estado</th>
            <th scope="col" class="progress-col">Avance</th>
            <th scope="col">Inicio</th>
            <th scope="col">Fin previsto</th>
            <th scope="col">Peso</th>
            <th scope="col"><span class="hidden">Acciones</span></th>
          </tr>
        </thead>
        <tbody>
          @for (stage of stages(); track stage.id) {
            <tr>
              <td class="num ov-num">{{ number(stage) }}</td>
              <td class="name">{{ stage.name }}</td>
              <td>
                <span class="chip" [class.chip-now]="stage.status === 'IN_PROGRESS'" [class.chip-late]="stage.status === 'DELAYED'"
                      [class.chip-next]="stage.status === 'PENDING'">{{ labels[stage.status] }}</span>
              </td>
              <td>
                <div class="progress">
                  <div class="bar" [class.late]="stage.status === 'DELAYED'"><span [style.width.%]="stage.progressPercent"></span></div>
                  <span class="ov-num">{{ stage.progressPercent }}%</span>
                </div>
              </td>
              <td class="muted">
                @if (stage.startedOn) { {{ stage.startedOn | shortDate: 'd MMM yy' }} }
                @else if (stage.plannedStart) { Prev. {{ stage.plannedStart | shortDate: 'd MMM yy' }} }
                @else { — }
              </td>
              <td [class.overdue]="overdue(stage)" [class.muted]="!overdue(stage)">
                @if (stage.plannedEnd) { {{ stage.plannedEnd | shortDate: 'd MMM yy' }}@if (overdue(stage)) { · pasó} } @else { — }
              </td>
              <td class="muted ov-num">{{ stage.weight }}</td>
              <td class="edit"><button type="button" class="ov-link" (click)="edit.emit(stage)">Editar</button></td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .wrap {
      overflow-x: auto;
    }

    table {
      width: 100%;
      min-width: 56rem;
      border-collapse: collapse;
      font-size: var(--ov-text-sm);
    }

    th {
      padding: var(--ov-space-3) var(--ov-space-2);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      text-align: left;
    }

    td {
      padding: var(--ov-space-3) var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
    }

    th:first-child,
    td:first-child {
      padding-left: var(--ov-space-5);
    }

    .edit {
      padding-right: var(--ov-space-5);
      text-align: right;
    }

    .progress-col {
      width: 11rem;
    }

    .num,
    .muted {
      color: var(--ov-ink-muted);
    }

    .name {
      font-weight: var(--ov-weight-medium);
    }

    .chip {
      display: inline-block;
      padding: 0.2rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      color: var(--ov-status-completed);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      white-space: nowrap;
    }

    .chip-now {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .chip-late {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .chip-next {
      border: 1px solid var(--ov-line);
      background: transparent;
      color: var(--ov-ink-muted);
    }

    .progress {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
    }

    .bar {
      flex: 1;
      height: 0.375rem;
      overflow: hidden;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .bar span {
      display: block;
      height: 100%;
      background: var(--ov-accent);
    }

    .bar.late span {
      background: var(--ov-status-delayed);
    }

    .overdue {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-semibold);
    }

    .hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
    }
  `,
})
export class StageTableComponent {
  readonly stages = input.required<Stage[]>();
  readonly today = input.required<string>();
  readonly edit = output<Stage>();

  protected readonly labels = STAGE_STATE_LABELS;

  protected number(stage: Stage): string {
    return String(stage.orderIndex).padStart(2, '0');
  }

  protected overdue(stage: Stage): boolean {
    return isOverdue(stage, this.today());
  }
}
