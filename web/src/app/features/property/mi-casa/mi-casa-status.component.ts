import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PublicUnitView } from '../../../core/api/api.models';
import { todayLocal } from '../../../shared/time/local-date';
import { deliveryMonth, entriesThisMonth, longYearMonth, monthsLeft, schedule } from '../buyer-derivations';

/**
 * Las tres tarjetas debajo de la portada: el avance en un anillo, la entrega con su estado y lo
 * que se está haciendo ahora. "En tiempo" sólo cuando se puede afirmar; una demora se dice con
 * su motivo y en tono neutro, nunca en rojo. Una tarjeta sin dato no aparece.
 */
@Component({
  selector: 'app-mi-casa-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="cards" aria-label="Estado de la obra">
      <div class="card ring-card">
        <div class="ring" role="img" [attr.aria-label]="view().overallProgressPercent + ' por ciento de avance'"
             [style.--done]="view().overallProgressPercent + '%'">
          <span class="ov-num">{{ view().overallProgressPercent }}%</span>
        </div>
        <div class="ring-text">
          <p class="label">Avance de tu casa</p>
          @if (stageCount().total > 0) {
            <p class="strong">{{ stageCount().done }} de {{ stageCount().total }} etapas terminadas</p>
          }
          @if (thisMonth(); as line) {
            <p class="muted">{{ line }}</p>
          }
        </div>
      </div>

      @if (delivery(); as month) {
        <div class="card">
          <div class="row">
            <p class="label">Entrega estimada</p>
            @if (state(); as current) {
              <span class="badge" [attr.data-kind]="current.kind">{{ current.kind === 'ON_TIME' ? 'En tiempo' : 'Con demora' }}</span>
            }
          </div>
          <p class="ov-display delivery">{{ month }}</p>
          @if (left(); as remaining) {
            <p class="muted">{{ remaining }}</p>
          }
          @if (state()?.reason; as reason) {
            <p class="reason">{{ reason }}</p>
          }
        </div>
      }

      @if (working().length > 0) {
        <div class="card">
          <p class="label">Ahora se está trabajando en</p>
          @for (stage of working(); track stage.name) {
            <div class="work">
              <p class="row"><span class="strong">{{ stage.name }}</span><span class="muted ov-num">{{ stage.progressPercent }}%</span></p>
              <div class="ov-progress" aria-hidden="true"><span [style.width.%]="stage.progressPercent"></span></div>
            </div>
          }
        </div>
      }
    </section>
  `,
  styles: `
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
      gap: var(--ov-space-4);
    }

    .card {
      display: grid;
      align-content: start;
      gap: var(--ov-space-2);
      padding: var(--ov-space-6);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
    }

    .ring-card {
      display: flex;
      align-items: center;
      gap: var(--ov-space-5);
    }

    .ring {
      display: grid;
      flex: none;
      place-items: center;
      width: 6rem;
      height: 6rem;
      border-radius: 50%;
      background: conic-gradient(var(--ov-accent) 0 var(--done), var(--ov-surface-muted) var(--done) 100%);
    }

    .ring span {
      display: grid;
      place-items: center;
      width: 4.75rem;
      height: 4.75rem;
      border-radius: 50%;
      background: var(--ov-surface);
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .ring-text {
      display: grid;
      gap: var(--ov-space-1);
    }

    .label,
    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .strong {
      font-weight: var(--ov-weight-semibold);
    }

    .row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      font-size: var(--ov-text-sm);
    }

    .badge {
      padding: 0.25rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      color: var(--ov-ink);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .badge[data-kind='ON_TIME'] {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .delivery {
      font-size: var(--ov-text-xl);
    }

    .reason {
      padding-top: var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      font-size: var(--ov-text-sm);
      line-height: 1.5;
    }

    .work {
      display: grid;
      gap: var(--ov-space-1);
    }
  `,
})
export class MiCasaStatusComponent {
  readonly view = input.required<PublicUnitView>();
  private readonly today = todayLocal();

  protected readonly stageCount = computed(() => ({
    done: this.view().stages.filter(stage => stage.status === 'COMPLETED').length,
    total: this.view().stages.length,
  }));
  protected readonly thisMonth = computed(() => entriesThisMonth(this.view(), this.today));
  protected readonly delivery = computed(() => {
    const month = deliveryMonth(this.view());
    return month ? longYearMonth(month) : null;
  });
  protected readonly left = computed(() => {
    const month = deliveryMonth(this.view());
    return month ? monthsLeft(month, this.today) : null;
  });
  protected readonly state = computed(() => schedule(this.view(), this.today));
  protected readonly working = computed(() => [...this.view().stages]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .filter(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED'));
}
