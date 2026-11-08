import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Stage } from '../../core/api/api.models';

/**
 * "Etapas en curso" de Hoy: las que se están trabajando, con su avance; una demorada lo dice en
 * ámbar, nunca en rojo. Con `publish`, cada una lleva "Publicar", que abre "Publicar avance" con
 * esa etapa ya elegida (`?etapa=`), como en escritorio.
 */
@Component({
  selector: 'app-field-stages',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (stages().length === 0) {
      <p class="muted">No hay etapas en curso.</p>
    } @else {
      <ul class="stages">
        @for (stage of stages(); track stage.id) {
          <li class="stage" [class.delayed]="stage.status === 'DELAYED'">
            <span class="name">
              {{ stage.name }}
              @if (stage.status === 'DELAYED') {
                <span class="late">Demorada</span>
              }
            </span>
            <span class="ov-num pct">{{ stage.progressPercent }}%</span>
            <span class="ov-progress bar" aria-hidden="true"><span [style.width.%]="stage.progressPercent"></span></span>
            @if (publish()) {
              <a class="publish" [routerLink]="['/hoy', unitId(), 'avance']" [queryParams]="{ etapa: stage.id }"
                 [attr.aria-label]="'Publicar un avance de ' + stage.name">Publicar</a>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .stages {
      display: grid;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .stage {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      grid-template-areas: 'name pct' 'bar bar';
      align-items: center;
      gap: var(--ov-space-2) var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .stage:first-child {
      border-top: 0;
    }

    .name {
      grid-area: name;
      font-weight: var(--ov-weight-medium);
    }

    .pct {
      grid-area: pct;
      font-weight: var(--ov-weight-semibold);
    }

    .bar {
      grid-area: bar;
    }

    .late {
      margin-left: var(--ov-space-2);
      padding: 0.15rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .delayed .bar > span {
      background: var(--ov-status-delayed);
    }

    .stage:has(.publish) {
      grid-template-columns: minmax(0, 1fr) auto auto;
      grid-template-areas: 'name pct publish' 'bar bar publish';
    }

    .publish {
      grid-area: publish;
      display: inline-flex;
      align-items: center;
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
      text-decoration: none;
    }

    .muted {
      margin: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class FieldStagesComponent {
  /** Las etapas abiertas, ya en orden (`openStages`). */
  readonly stages = input.required<Stage[]>();
  readonly unitId = input.required<string>();
  readonly publish = input(false);
}
