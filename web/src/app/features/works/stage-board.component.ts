import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { Stage } from '../../core/api/api.models';
import { StageBoard } from './stage-board';
import { StageCardComponent } from './stage-card.component';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * "Por estado": lo que se hace ahora como tarjetas, con su avance, sus fechas y su último
 * avance; lo que viene, con "Empezar"; y lo terminado como píldoras. Una etapa demorada o
 * vencida va en ámbar, nunca en rojo, y siempre con la palabra que lo explica.
 */
@Component({
  selector: 'app-stage-board',
  imports: [ShortDatePipe, RouterLink, MatIconModule, StageCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = board();
    @if (current.active.length > 0) {
      <section class="group" aria-labelledby="active-title">
        <h3 id="active-title" class="group-title">En curso <span class="count">· {{ current.active.length }}</span></h3>
        <div class="cards">
          @for (card of current.active; track card.stage.id) {
            <app-stage-card [card]="card">
              <div class="actions">
                <a class="ov-btn ov-btn--secondary grow" [routerLink]="historyLink()" [queryParams]="{ etapa: card.stage.id }">Actualizar avance</a>
                <button type="button" class="ov-link" (click)="edit.emit(card.stage)">Editar</button>
              </div>
            </app-stage-card>
          }
        </div>
      </section>
    }

    @if (current.upcoming.length > 0) {
      <section class="group" aria-labelledby="upcoming-title">
        <h3 id="upcoming-title" class="group-title">Lo que viene <span class="count">· {{ current.upcoming.length }}</span></h3>
        <ul class="ov-card rows">
          @for (card of current.upcoming; track card.stage.id) {
            <li class="row">
              <span class="num ov-num">{{ number(card.stage) }}</span>
              <span class="row-name">{{ card.stage.name }}</span>
              @if (card.overdue) {
                <span class="overdue">Fin previsto {{ card.stage.plannedEnd | shortDate: 'd MMM' }} · pasó</span>
              } @else if (card.stage.plannedStart) {
                <span class="muted">Inicio previsto {{ card.stage.plannedStart | shortDate: 'd MMM' }}</span>
              }
              <button type="button" class="ov-btn ov-btn--secondary start" [disabled]="busyId() === card.stage.id"
                      (click)="start.emit(card.stage)">{{ busyId() === card.stage.id ? 'Empezando…' : 'Empezar' }}</button>
              <button type="button" class="ov-link" (click)="edit.emit(card.stage)">Editar</button>
            </li>
          }
        </ul>
      </section>
    }

    @if (current.done.length > 0) {
      <section class="group" aria-labelledby="done-title">
        <h3 id="done-title" class="group-title">Terminadas <span class="count">· {{ current.done.length }}</span></h3>
        <ul class="pills">
          @for (card of current.done; track card.stage.id) {
            <li>
              <button type="button" class="pill" (click)="edit.emit(card.stage)" [attr.aria-label]="'Editar ' + card.stage.name">
                <mat-icon svgIcon="check_circle" aria-hidden="true" />{{ card.stage.name }}
                @if (card.stage.completedOn) { <span class="muted">{{ card.stage.completedOn | shortDate: 'MMM yy' }}</span> }
              </button>
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--ov-space-6);
    }

    .group {
      display: grid;
      gap: var(--ov-space-3);
    }

    .group-title {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .count,
    .muted,
    .num {
      color: var(--ov-ink-muted);
      font-weight: var(--ov-weight-regular);
    }

    .num {
      font-size: var(--ov-text-xs);
    }

    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 18.75rem), 1fr));
      gap: var(--ov-space-3);
    }

    .overdue {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .grow {
      flex: 1;
    }

    .rows,
    .pills {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2) var(--ov-space-4);
      padding: var(--ov-space-3) var(--ov-space-5);
      font-size: var(--ov-text-sm);
    }

    .row + .row {
      border-top: 1px solid var(--ov-line);
    }

    .row-name {
      flex: 1 1 12rem;
      font-size: var(--ov-text-md);
      font-weight: var(--ov-weight-medium);
    }

    .start {
      min-height: 2.75rem;
    }

    .pills {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .pill {
      display: inline-flex;
      align-items: center;
      gap: var(--ov-space-2);
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .pill mat-icon {
      width: 1rem;
      height: 1rem;
      color: var(--ov-accent);
    }
  `,
})
export class StageBoardComponent {
  readonly board = input.required<StageBoard>();
  /** La Historia de la casa o de lo común: "Actualizar avance" abre ahí su formulario. */
  readonly historyLink = input.required<string[]>();
  readonly busyId = input<string | null>(null);
  readonly edit = output<Stage>();
  readonly start = output<Stage>();

  protected number(stage: Stage): string {
    return String(stage.orderIndex).padStart(2, '0');
  }
}
