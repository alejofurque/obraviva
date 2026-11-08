import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { StageStatus } from '../../core/api/api.models';
import { STAGE_STATUS_LABELS } from './stage-status';

/**
 * "Demorada" es un estado de primera clase, con el mismo peso visual que los demás: un
 * portal que esconde las demoras pierde credibilidad. Las etiquetas son las de
 * `stage-status.ts`, las mismas de toda la parte interna.
 */
export { STAGE_STATUS_LABELS };

/**
 * Estado de una etapa u obra. El nombre del estado va siempre escrito: el color
 * acompaña, pero un color solo no informa y no lo distingue quien no ve bien el rojo
 * y el verde.
 */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `{{ label() }}`,
  host: {
    class: 'status-badge',
    '[attr.data-status]': 'status()',
  },
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      min-height: 1.625rem;
      padding: 0 var(--ov-space-3);
      border: 1px solid transparent;
      border-radius: var(--ov-radius-pill);
      color: var(--badge-ink);
      background: var(--badge-soft);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      white-space: nowrap;
    }

    :host([data-status='PENDING']) {
      --badge-ink: var(--ov-status-pending);
      --badge-soft: var(--ov-status-pending-soft);
      border-color: var(--ov-line);
    }

    :host([data-status='IN_PROGRESS']) {
      --badge-ink: var(--ov-status-in-progress);
      --badge-soft: var(--ov-status-in-progress-soft);
    }

    :host([data-status='COMPLETED']) {
      --badge-ink: var(--ov-status-completed);
      --badge-soft: var(--ov-status-completed-soft);
    }

    :host([data-status='DELAYED']) {
      --badge-ink: var(--ov-status-delayed);
      --badge-soft: var(--ov-status-delayed-soft);
    }
  `,
})
export class StatusBadgeComponent {
  readonly status = input.required<StageStatus>();

  protected readonly label = computed(() => STAGE_STATUS_LABELS[this.status()]);
}
