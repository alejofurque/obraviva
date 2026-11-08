import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { AssignedHouse, Stage } from '../../core/api/api.models';
import { UPDATE_STATUSES, clampPercent } from '../../shared/ui/stage-update.component';
import { ReviewCard } from './audio-review';
import { PendingPhotosComponent, PendingPhotosState } from './pending-photos.component';

/**
 * Una tarjeta de la revisión de un audio: un avance que va a ver la familia. Arriba la etapa
 * y "Descartar"; el texto que propuso la IA, editable; si propuso un estado, aceptarlo, elegirlo
 * y, si hace falta, el porcentaje con ±5 (la IA nunca lo propone); y sus fotos. Cada cambio sale
 * como `patch` y lo aplica la pantalla.
 */
@Component({
  selector: 'app-field-review-card',
  imports: [PendingPhotosComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = card();
    <section class="card" [attr.aria-label]="'Avance ' + position()">
      <div class="top">
        <select class="stage" [id]="'card-stage-' + current.key" aria-label="Etapa" [disabled]="disabled()"
                (change)="patch.emit({ stageId: $any($event.target).value || null })">
          <option value="" [selected]="!current.stageId">General de la casa</option>
          @for (stage of stages(); track stage.id) {
            <option [value]="stage.id" [selected]="stage.id === current.stageId">{{ stage.name }}</option>
          }
        </select>
        <button type="button" class="remove-card" [disabled]="disabled()" (click)="remove.emit()">Descartar</button>
      </div>
      <textarea class="ov-input" rows="3" maxlength="2000" aria-label="Qué se hizo" [disabled]="disabled()"
                [value]="current.description" (input)="patch.emit({ description: $any($event.target).value })"></textarea>

      @if (current.proposedStatus && current.stageId) {
        <div class="stage-change">
          <label class="switch">
            <input type="checkbox" role="switch" [checked]="current.acceptStatus" [disabled]="disabled()"
                   (change)="patch.emit({ acceptStatus: $any($event.target).checked })" />
            <span>Actualizar la etapa a {{ statusLabel(current.proposedStatus) }}</span>
          </label>
          @if (current.acceptStatus) {
            <div class="options" role="radiogroup" aria-label="Estado">
              @for (option of statuses; track option.value) {
                <label [class.active]="current.status === option.value" [attr.data-status]="option.value">
                  <input type="radio" [name]="'card-status-' + current.key" [value]="option.value"
                         [checked]="current.status === option.value" (change)="patch.emit({ status: option.value })" />
                  {{ option.label }}
                </label>
              }
            </div>
            @if (current.status === 'COMPLETED') {
              <p class="hint">Completada, la etapa queda en 100%.</p>
            } @else {
              <div class="percent">
                <span>Avance de la etapa</span>
                <div class="stepper">
                  <button type="button" class="step" aria-label="Bajar 5%" (click)="step(-5)">−5</button>
                  <input class="ov-num" type="number" inputmode="numeric" min="0" max="100" aria-label="Porcentaje de la etapa"
                         [value]="current.percent ?? ''" [placeholder]="'Sin cambiar (' + currentPercent() + '%)'"
                         (change)="setPercent($any($event.target).value)" />
                  <button type="button" class="step" aria-label="Subir 5%" (click)="step(5)">+5</button>
                </div>
              </div>
            }
          }
        </div>
      }

      <app-pending-photos [developmentId]="house().developmentId" [unitId]="house().unitId" [disabled]="disabled()"
                          [sectors]="house().sectors" (changed)="photos.emit($event)" />
    </section>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .card,
    .stage-change,
    .percent {
      display: grid;
      gap: var(--ov-space-3);
    }

    .card {
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-2);
    }

    .stage {
      flex: 1;
      width: 100%;
      min-width: 0;
      min-height: 3rem;
      padding: 0 var(--ov-space-3);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-weight: var(--ov-weight-semibold);
    }

    .remove-card {
      flex: none;
      min-height: 3rem;
      padding: 0 var(--ov-space-2);
      border: 0;
      background: none;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .switch {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 3rem;
      font-weight: var(--ov-weight-semibold);
    }

    .switch input {
      width: 1.25rem;
      height: 1.25rem;
      accent-color: var(--ov-accent);
    }

    .options {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-2);
    }

    .options label {
      display: grid;
      place-items: center;
      min-height: 3rem;
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .options label.active {
      border-color: var(--ov-accent);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .options label.active[data-status='DELAYED'] {
      border-color: var(--ov-status-delayed);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .options label:focus-within {
      outline: 2px solid var(--ov-accent);
    }

    .options input {
      position: absolute;
      opacity: 0;
    }

    .percent span,
    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .stepper {
      display: grid;
      grid-template-columns: 3.5rem minmax(0, 1fr) 3.5rem;
      gap: var(--ov-space-2);
    }

    .step,
    .stepper input {
      height: 3.5rem;
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      background: var(--ov-canvas);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
      text-align: center;
    }

    .stepper input {
      min-width: 0;
      background: var(--ov-surface);
      font-size: var(--ov-text-md);
    }
  `,
})
export class FieldReviewCardComponent {
  readonly card = input.required<ReviewCard>();
  readonly position = input.required<number>();
  readonly stages = input.required<Stage[]>();
  readonly house = input.required<AssignedHouse>();
  readonly disabled = input(false);
  readonly patch = output<Partial<ReviewCard>>();
  readonly photos = output<PendingPhotosState>();
  readonly remove = output<void>();

  protected readonly statuses = UPDATE_STATUSES;
  protected readonly currentPercent = computed(() =>
    this.stages().find(stage => stage.id === this.card().stageId)?.progressPercent ?? 0);

  protected statusLabel(status: string): string {
    return UPDATE_STATUSES.find(option => option.value === status)?.label.toLowerCase() ?? status;
  }

  /** ±5 arranca del porcentaje que la etapa tiene hoy. */
  protected step(delta: number): void {
    this.patch.emit({ percent: clampPercent((this.card().percent ?? this.currentPercent()) + delta) });
  }

  protected setPercent(value: string): void {
    this.patch.emit({ percent: value.trim() === '' ? null : clampPercent(Number(value)) });
  }
}
