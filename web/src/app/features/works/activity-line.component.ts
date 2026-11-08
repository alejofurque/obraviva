import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ActivityItem } from '../../core/api/api.models';
import { activityLine, changeChip, relativeWhen } from './overview-derivations';

/**
 * Una novedad en una línea, para los resúmenes: quién y qué, el cambio de etapa si lo hubo y
 * dónde y cuándo. El punto verde es lo sin ver. El formato completo, con fotos y "Corregir",
 * es el de Novedades (`ActivityItemComponent`).
 */
@Component({
  selector: 'app-activity-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="dot" [class.unseen]="!!item().unseen" [attr.aria-label]="item().unseen ? 'Sin ver' : null"></span>
    <div class="body">
      <p><strong>{{ line().who }}</strong>{{ ' ' }}<span class="what">{{ line().what }}</span></p>
      @if (chip(); as change) {
        <span class="chip ov-num">{{ change }}</span>
      }
      <p class="where">
        @if (showUnit()) { {{ item().unitCode ?? 'Obras comunes' }} · }{{ when() }}
      </p>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
      font-size: var(--ov-text-sm);
      line-height: 1.4;
    }

    .dot {
      flex: none;
      width: 0.5rem;
      height: 0.5rem;
      margin-top: 0.4rem;
      border-radius: 50%;
      background: transparent;
    }

    .dot.unseen {
      background: var(--ov-accent);
    }

    .body {
      display: grid;
      justify-items: start;
      gap: 0.125rem;
      min-width: 0;
    }

    .what {
      color: var(--ov-ink-muted);
    }

    .chip {
      margin-top: var(--ov-space-1);
      padding: 0.125rem var(--ov-space-2);
      border-radius: var(--ov-radius-sm);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
    }

    .where {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }
  `,
})
export class ActivityLineComponent {
  readonly item = input.required<ActivityItem>();
  /** En la casa, la casa no se repite. */
  readonly showUnit = input(true);

  protected readonly line = computed(() => activityLine(this.item()));
  protected readonly chip = computed(() => changeChip(this.item().entry));
  protected readonly when = computed(() => relativeWhen(this.item().at));
}
