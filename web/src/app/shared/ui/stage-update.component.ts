import { ChangeDetectionStrategy, Component, Directive, computed, effect, input, model, signal, untracked } from '@angular/core';
import { Stage, StageUpdatePayload } from '../../core/api/api.models';
import { STAGE_STATUS_LABELS } from './stage-status';

type UpdateStatus = StageUpdatePayload['status'];

/** Estados que se declaran con un avance. Pendiente no: una etapa con avance ya arrancó. */
export const UPDATE_STATUSES: { value: UpdateStatus; label: string }[] = [
  { value: 'IN_PROGRESS', label: STAGE_STATUS_LABELS.IN_PROGRESS },
  { value: 'DELAYED', label: STAGE_STATUS_LABELS.DELAYED },
  { value: 'COMPLETED', label: STAGE_STATUS_LABELS.COMPLETED },
];

/** El porcentaje que se puede declarar: entero, de 0 a 100. */
export function clampPercent(value: number): number {
  return Number.isFinite(value) ? Math.min(100, Math.max(0, Math.round(value))) : 0;
}

/** El estado que propone el bloque para una etapa: el que tiene, si es uno de los declarables. */
export function proposedStatus(stage: Pick<Stage, 'status'>): UpdateStatus {
  return stage.status === 'PENDING' ? 'IN_PROGRESS' : stage.status;
}

/**
 * Lo que comparten las dos formas del bloque de la etapa: la compacta de los formularios
 * (`StageUpdateComponent`) y la grande del trabajo de campo, con botones para el pulgar. Las
 * reglas son las mismas, así un envío desde la obra y uno desde la Historia no difieren.
 */
@Directive()
export abstract class StageUpdateBase {
  readonly stage = input.required<Stage>();
  readonly disabled = input(false);
  /** Arranca prendido: se llegó a actualizar el avance de esta etapa ("Actualizar avance" en Etapas). */
  readonly startOn = input(false);
  /** Prefijo de los ids, para que dos formularios en la misma página no choquen. */
  readonly idPrefix = input('stage');
  /** Lo que viaja como `stageUpdate`; nulo si el interruptor está apagado. */
  readonly update = model<StageUpdatePayload | null>(null);

  protected readonly statuses = UPDATE_STATUSES;
  protected readonly on = signal(false);
  protected readonly percent = signal(0);
  protected readonly status = signal<UpdateStatus>('IN_PROGRESS');
  protected readonly locked = computed(() => this.status() === 'COMPLETED');
  private readonly stageId = computed(() => this.stage().id);
  private percentBeforeCompleted = 0;

  constructor() {
    effect(() => {
      this.stageId();
      untracked(() => this.reset());
    });
  }

  protected toggle(on: boolean): void {
    this.on.set(on);
    this.emit();
  }

  protected step(delta: number): void {
    this.percent.set(clampPercent(this.percent() + delta));
    this.emit();
  }

  protected setPercent(value: string): void {
    this.percent.set(clampPercent(Number(value)));
    this.emit();
  }

  protected chooseStatus(status: UpdateStatus): void {
    if (status === 'COMPLETED' && this.status() !== 'COMPLETED') {
      this.percentBeforeCompleted = this.percent();
      this.percent.set(100);
    } else if (status !== 'COMPLETED' && this.status() === 'COMPLETED') {
      this.percent.set(this.percentBeforeCompleted);
    }
    this.status.set(status);
    this.emit();
  }

  private reset(): void {
    const stage = this.stage();
    this.on.set(this.startOn());
    this.percent.set(stage.progressPercent);
    this.percentBeforeCompleted = stage.progressPercent;
    this.status.set(proposedStatus(stage));
    this.emit();
  }

  private emit(): void {
    this.update.set(this.on() ? { progressPercent: this.percent(), status: this.status() } : null);
  }
}

/**
 * "Actualizar el avance de la etapa" dentro de un formulario de avance: porcentaje con ±5 y
 * estado. Es el mismo bloque en "Publicar avance" del encargado y en la Historia del dueño,
 * y viaja como `stageUpdate` en el mismo envío que el avance: sale todo junto o nada.
 *
 * <p>Completado propone 100 y lo bloquea, como hace el servidor; al salir de Completado
 * vuelve el número que había. Con una etapa nueva, arranca de lo que la etapa tiene hoy.
 */
@Component({
  selector: 'app-stage-update',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let chosen = stage();
    <section class="update" aria-label="Avance de la etapa">
      <label class="switch">
        <input type="checkbox" role="switch" [checked]="on()" [disabled]="disabled()"
               (change)="toggle($any($event.target).checked)" />
        <span>Actualizar el avance de la etapa</span>
      </label>

      @if (on()) {
        <div class="ov-field">
          <label class="ov-eyebrow" [for]="idPrefix() + '-percent'">Porcentaje de {{ chosen.name }}</label>
          <div class="stepper">
            <button type="button" class="ov-btn ov-btn--secondary step" aria-label="Bajar 5%"
                    [disabled]="locked() || disabled() || percent() <= 0" (click)="step(-5)">−5</button>
            <input [id]="idPrefix() + '-percent'" class="ov-input ov-num" type="number" inputmode="numeric" min="0" max="100"
                   [value]="percent()" [disabled]="locked() || disabled()" (change)="setPercent($any($event.target).value)" />
            <button type="button" class="ov-btn ov-btn--secondary step" aria-label="Subir 5%"
                    [disabled]="locked() || disabled() || percent() >= 100" (click)="step(5)">+5</button>
          </div>
          @if (locked()) {
            <p class="hint">Completada, la etapa queda en 100%.</p>
          } @else if (percent() < chosen.progressPercent) {
            <p class="warning">Baja el avance que ve el comprador: estaba en {{ chosen.progressPercent }}%.</p>
          } @else {
            <p class="hint">Estaba en {{ chosen.progressPercent }}%.</p>
          }
        </div>

        <fieldset class="ov-field statuses" [disabled]="disabled()">
          <legend class="ov-eyebrow">Estado</legend>
          <div class="segmented">
            @for (option of statuses; track option.value) {
              <label [class.active]="status() === option.value">
                <input type="radio" [name]="idPrefix() + '-status'" [value]="option.value" [checked]="status() === option.value"
                       (change)="chooseStatus(option.value)" />
                {{ option.label }}
              </label>
            }
          </div>
          @if (status() === 'DELAYED') {
            <p class="hint">El texto del avance es la explicación que ve el comprador: contá por qué se demora.</p>
          }
        </fieldset>
      }
    </section>
  `,
  styles: `
    .update {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
    }

    .switch {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 2.75rem;
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .switch input {
      width: 1.25rem;
      height: 1.25rem;
      accent-color: var(--ov-accent);
    }

    .stepper {
      display: grid;
      grid-template-columns: 3.5rem minmax(0, 1fr) 3.5rem;
      gap: var(--ov-space-2);
      max-width: 20rem;
    }

    .stepper .ov-input {
      text-align: center;
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .step {
      padding: 0;
    }

    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .warning {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
    }

    .statuses {
      margin: 0;
      padding: 0;
      border: 0;
    }

    .segmented {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-1);
      max-width: 30rem;
      padding: var(--ov-space-1);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .segmented label {
      display: grid;
      place-items: center;
      min-height: 2.75rem;
      padding: 0 var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      font-size: var(--ov-text-sm);
      text-align: center;
      cursor: pointer;
    }

    .segmented label.active {
      background: var(--ov-surface);
      font-weight: var(--ov-weight-semibold);
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
    }

    .segmented label:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 1px;
    }

    .segmented input {
      position: absolute;
      opacity: 0;
      pointer-events: none;
    }
  `,
})
export class StageUpdateComponent extends StageUpdateBase {}
