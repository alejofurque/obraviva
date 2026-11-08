import { ChangeDetectionStrategy, Component, ElementRef, OnInit, afterNextRender, inject, input, output, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { Stage, StageStatus, UpdateStagePlanPayload } from '../../core/api/api.models';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { todayLocal } from '../../shared/time/local-date';
import { STAGE_STATUS_OPTIONS } from '../../shared/ui/stage-status';

const STATUS_OPTIONS: { value: StageStatus; label: string }[] = STAGE_STATUS_OPTIONS;

/**
 * "Editar" una etapa: corregir sin publicar (estado, avance y el día del cambio) y, para el
 * dueño, el detalle (nombre, qué incluye, fechas previstas y peso). Lo que cambia acá lo ve
 * el comprador en el número, pero no queda en la historia: para contárselo, "Actualizar avance".
 *
 * <p>Cada cambio se guarda solo, como siempre: el estado al elegirlo, el avance al soltar el
 * deslizador; el detalle con su botón.
 */
@Component({
  selector: 'app-stage-edit',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = stage();
    <dialog #dialog class="sheet" [attr.aria-labelledby]="'edit-title-' + current.id" (close)="closed.emit()">
      <header class="head">
        <div>
          <p class="ov-eyebrow">Etapa {{ current.orderIndex }}</p>
          <h3 [id]="'edit-title-' + current.id">{{ current.name }}</h3>
        </div>
        <button type="button" class="ov-link" (click)="close()">Cerrar</button>
      </header>

      <section class="block" aria-label="Corregir sin publicar">
        <p class="muted">Corrige el número sin publicar un avance. Para contárselo al comprador, usá "Actualizar avance".</p>
        <div class="controls">
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'status-' + current.id">Estado</label>
            <select class="ov-input" [id]="'status-' + current.id" [value]="current.status" [disabled]="saving()"
                    (change)="changeStatus($any($event.target).value)">
              @for (option of statusOptions; track option.value) {
                <option [value]="option.value" [selected]="option.value === current.status">{{ option.label }}</option>
              }
            </select>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'progress-' + current.id">Avance</label>
            <div class="progress-row">
              <input type="range" min="0" max="100" step="5" [id]="'progress-' + current.id"
                     [value]="draft() ?? current.progressPercent" [disabled]="saving() || current.status === 'COMPLETED'"
                     (input)="draft.set($any($event.target).valueAsNumber)" (change)="saveProgress()" />
              <span class="ov-num percent">{{ draft() ?? current.progressPercent }}%</span>
            </div>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'when-' + current.id">Fecha del cambio</label>
            <input class="ov-input" type="date" [id]="'when-' + current.id" [max]="today"
                   [value]="when()" (change)="when.set($any($event.target).value || today)" />
          </div>
        </div>
      </section>

      @if (owner()) {
        <form [formGroup]="form" (ngSubmit)="saveDetail()" class="block detail" novalidate>
          <div class="ov-field wide">
            <label class="ov-eyebrow" [for]="'name-' + current.id">Nombre</label>
            <input class="ov-input" [id]="'name-' + current.id" formControlName="name" maxlength="200" />
          </div>
          <div class="ov-field wide">
            <label class="ov-eyebrow" [for]="'description-' + current.id">Qué incluye</label>
            <textarea class="ov-input" [id]="'description-' + current.id" formControlName="description" maxlength="2000"
                      placeholder="Los trabajos de la etapa, como se los contarías al comprador."></textarea>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'start-' + current.id">Inicio previsto</label>
            <input class="ov-input" type="date" [id]="'start-' + current.id" formControlName="plannedStart" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'end-' + current.id">Fin previsto</label>
            <input class="ov-input" type="date" [id]="'end-' + current.id" formControlName="plannedEnd" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" [for]="'weight-' + current.id">Peso en el avance</label>
            <input class="ov-input" type="number" min="1" max="100" [id]="'weight-' + current.id" formControlName="weight" />
          </div>
          <div class="actions wide">
            <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || form.invalid">
              {{ saving() ? 'Guardando…' : 'Guardar detalle' }}
            </button>
          </div>
        </form>
      }

      @if (error(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }
    </dialog>
  `,
  styles: `
    .sheet {
      width: min(40rem, calc(100vw - 2 * var(--ov-gutter)));
      max-height: calc(100dvh - 2 * var(--ov-space-6));
      padding: var(--ov-space-6);
      border: 0;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .sheet::backdrop {
      background: color-mix(in srgb, var(--ov-ink) 40%, transparent);
    }

    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .block {
      display: grid;
      gap: var(--ov-space-4);
      padding-top: var(--ov-space-5);
      margin-top: var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
    }

    .controls,
    .detail {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .progress-row {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 2.75rem;
    }

    .progress-row input {
      flex: 1;
      accent-color: var(--ov-accent);
    }

    .percent {
      min-width: 3rem;
      text-align: right;
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    textarea {
      min-height: 6rem;
    }
  `,
})
export class StageEditComponent implements OnInit {
  readonly stage = input.required<Stage>();
  readonly owner = input(false);
  readonly saved = output<Stage>();
  readonly closed = output<void>();

  private readonly stagesService = inject(StagesService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected readonly statusOptions = STATUS_OPTIONS;
  protected readonly today = todayLocal();
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly draft = signal<number | null>(null);
  protected readonly when = signal(this.today);

  protected readonly form = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
    plannedStart: [''],
    plannedEnd: [''],
    weight: [1, [Validators.required, Validators.min(1), Validators.max(100)]],
  });

  constructor() {
    // jsdom no tiene showModal: el contenido igual está, y en el navegador abre modal.
    afterNextRender(() => this.dialog().nativeElement.showModal?.());
  }

  ngOnInit(): void {
    const stage = this.stage();
    this.form.setValue({
      name: stage.name,
      description: stage.description ?? '',
      plannedStart: stage.plannedStart ?? '',
      plannedEnd: stage.plannedEnd ?? '',
      weight: stage.weight,
    });
  }

  protected close(): void {
    const dialog = this.dialog().nativeElement;
    if (dialog.open) {
      dialog.close();
    } else {
      this.closed.emit();
    }
  }

  protected changeStatus(status: StageStatus): void {
    if (status === this.stage().status) {
      return;
    }
    const date = this.when();
    this.apply(this.stagesService.updateStatus(this.stage().id, status, date === this.today ? undefined : date),
      'No se pudo cambiar el estado.');
  }

  protected saveProgress(): void {
    const value = this.draft();
    if (value === null || value === this.stage().progressPercent) {
      return;
    }
    this.apply(this.stagesService.updatePlan(this.stage().id, { progressPercent: value }), 'No se pudo guardar el avance.');
  }

  protected saveDetail(): void {
    const value = this.form.getRawValue();
    if (value.plannedStart && value.plannedEnd && value.plannedEnd < value.plannedStart) {
      this.error.set('El fin previsto no puede ser anterior al inicio.');
      return;
    }
    const changes: UpdateStagePlanPayload = { name: value.name.trim(), description: value.description.trim(), weight: value.weight };
    if (!value.plannedStart && !value.plannedEnd) {
      changes.clearPlannedDates = true;
    } else {
      changes.plannedStart = value.plannedStart || undefined;
      changes.plannedEnd = value.plannedEnd || undefined;
    }
    this.apply(this.stagesService.updatePlan(this.stage().id, changes), 'No se pudo guardar el detalle.', () => this.close());
  }

  private apply(request: Observable<Stage>, fallback: string, done: () => void = () => {}): void {
    this.saving.set(true);
    this.error.set(null);
    request.subscribe({
      next: updated => {
        this.saving.set(false);
        this.draft.set(null);
        this.saved.emit(updated);
        done();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.draft.set(null);
        this.error.set(errorMessageOf(error, fallback));
      },
    });
  }
}
