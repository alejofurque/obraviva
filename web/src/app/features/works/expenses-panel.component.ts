import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MoneyPipe } from '../../shared/money/money.pipe';
import { ExpenseResponse } from '../../core/api/api.models';
import { DashboardService } from '../../core/api/dashboard.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { UNSAVED_CHANGES } from '../../shared/ui/leave-guard';
import { todayLocal } from '../../shared/time/local-date';
import { formatAmount, parseAmount } from './amounts';
import { ShortDatePipe } from '../../shared/time/short-date';

export interface ExpenseStageOption {
  stageId: string;
  name: string;
}

/** Las etapas que se ofrecen al corregir un gasto de cierto lugar, y cómo se llama "sin etapa". */
export interface ExpenseStageChoices {
  stages: ExpenseStageOption[];
  generalLabel: string;
}

/** Monto obligatorio, escrito como en Argentina y mayor que cero. */
function amountValidator(control: AbstractControl<string>): ValidationErrors | null {
  const amount = parseAmount(control.value ?? '');
  return amount === null || amount === 'invalid' || amount <= 0 ? { amount: true } : null;
}

/**
 * Gastos de una casa, o los generales y de obras comunes del desarrollo: la lista, de más
 * nuevo a más viejo, y su carga y corrección.
 *
 * <p>"Cargar gasto" abre el formulario arriba de la lista; "Editar" convierte la fila en
 * ese mismo formulario con sus datos. Hay un solo formulario abierto a la vez. El concepto
 * es opcional: sin concepto la fila dice "Sin detalle".
 *
 * <p>Con `limit` son "Últimos gastos": los más nuevos y "Ver todos (N)". Donde la carga va
 * en la franja de arriba (`canAdd` en falso), la lista sólo corrige y borra.
 *
 * <p>En el desarrollo, `placeOf` cambia la columna Etapa por "Dónde" ("Casa 1 · Estructura") y
 * `choicesFor` da las etapas del lugar de cada gasto al corregirlo: la casa no cambia.
 */
@Component({
  selector: 'app-expenses-panel',
  imports: [ShortDatePipe, MoneyPipe, NgTemplateOutlet, ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="expenses-panel" aria-labelledby="expenses-title">
      <div class="head">
        <h3 id="expenses-title">{{ title() }}</h3>
        @if (canAdd() && mode() === null) {
          <button class="ov-btn ov-btn--primary" type="button" (click)="startAdding()">Cargar gasto</button>
        } @else if (hidden() > 0) {
          <button class="ov-link" type="button" (click)="expanded.set(true)">Ver todos ({{ sorted().length }})</button>
        }
      </div>

      @if (error(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }

      @if (mode() === 'add') {
        <ng-container *ngTemplateOutlet="formTemplate" />
      }

      <ng-template #formTemplate>
        <form [formGroup]="form" (ngSubmit)="save()" class="expense-form" novalidate>
          <div class="ov-field">
            <label class="ov-eyebrow" for="expense-date">Fecha</label>
            <input id="expense-date" class="ov-input" type="date" formControlName="incurredOn" [max]="today" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="expense-stage">Etapa</label>
            <select id="expense-stage" class="ov-input" formControlName="stageId">
              <option value="">{{ formChoices().generalLabel }}</option>
              @for (stage of formChoices().stages; track stage.stageId) {
                <option [value]="stage.stageId">{{ stage.name }}</option>
              }
            </select>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="expense-amount">Monto</label>
            <input id="expense-amount" class="ov-input" inputmode="decimal" formControlName="amount"
                   placeholder="Por ejemplo: 4.800 o 1.250,50" />
          </div>
          <div class="ov-field wide">
            <label class="ov-eyebrow" for="expense-description">Concepto</label>
            <input id="expense-description" class="ov-input" formControlName="description" maxlength="300"
                   placeholder="Por ejemplo: hormigón elaborado" />
          </div>
          @if (form.controls.amount.touched && form.controls.amount.invalid) {
            <p class="ov-error wide">El monto tiene que ser mayor que cero, escrito como 4.800 o 1.250,50.</p>
          }
          <div class="actions wide">
            <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || form.invalid">
              {{ saving() ? 'Guardando…' : 'Guardar' }}
            </button>
            <button class="ov-link" type="button" (click)="close()">Cancelar</button>
          </div>
        </form>
      </ng-template>

      @if (sorted().length === 0) {
        <p class="muted">Todavía no hay gastos cargados.</p>
      } @else {
        <table class="expense-table">
          <thead>
            <tr>
              <th scope="col">Fecha</th>
              <th scope="col">Concepto</th>
              <th scope="col">{{ placeOf() ? 'Dónde' : 'Etapa' }}</th>
              <th scope="col" class="num">Monto</th>
              <th scope="col"><span class="ov-visually-hidden">Acciones</span></th>
            </tr>
          </thead>
          <tbody>
            @for (expense of shown(); track expense.id) {
              @if (mode() === expense.id) {
                <tr class="editing">
                  <td colspan="5"><ng-container *ngTemplateOutlet="formTemplate" /></td>
                </tr>
              } @else {
                <tr>
                  <td class="date">{{ expense.incurredOn | shortDate: 'd MMM y' }}</td>
                  <td class="concept" [class.empty]="!expense.description">{{ expense.description || 'Sin detalle' }}</td>
                  <td class="stage">{{ placeOf()?.(expense) ?? stageName(expense.stageId) }}</td>
                  <td class="num amount">{{ expense.amount | money }}</td>
                  <td class="row-actions">
                    <button class="ov-link" type="button" (click)="startEditing(expense)">Editar</button>
                    <button class="ov-link" type="button" (click)="remove(expense)">Borrar</button>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    .expenses-panel {
      display: grid;
      gap: var(--ov-space-4);
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    h3 {
      font-size: var(--ov-text-lg);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .expense-form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr));
      gap: var(--ov-space-3) var(--ov-space-4);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .expense-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--ov-text-sm);
    }

    th,
    td {
      padding: var(--ov-space-3) var(--ov-space-2);
      border-bottom: 1px solid var(--ov-line);
      text-align: left;
      vertical-align: middle;
    }

    thead th {
      color: var(--ov-ink-muted);
      font-weight: var(--ov-weight-medium);
    }

    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    .date {
      white-space: nowrap;
      color: var(--ov-ink-muted);
    }

    .concept.empty {
      color: var(--ov-ink-muted);
    }

    .row-actions {
      text-align: right;
      white-space: nowrap;
    }

    .row-actions .ov-link + .ov-link {
      margin-left: var(--ov-space-3);
    }

    tr.editing td {
      padding: var(--ov-space-3) 0;
    }

    /* Teléfono: cada gasto en dos líneas, sin scroll horizontal. */
    @media (max-width: 40rem) {
      thead {
        display: none;
      }

      tr:not(.editing) {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr) auto;
        grid-template-areas:
          'concept concept amount'
          'date stage actions';
        gap: var(--ov-space-1) var(--ov-space-3);
        padding: var(--ov-space-3) 0;
        border-bottom: 1px solid var(--ov-line);
      }

      tr.editing {
        display: block;
      }

      tr.editing td {
        display: block;
      }

      td {
        padding: 0;
        border: 0;
      }

      .concept { grid-area: concept; }
      .amount { grid-area: amount; }
      .date { grid-area: date; }

      .stage {
        grid-area: stage;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .stage::before {
        content: '· ';
      }
      .row-actions { grid-area: actions; }
    }
  `,
})
export class ExpensesPanelComponent {
  /** Un gasto a medio cargar o editar. */
  leaveWarning(): string | null {
    return this.mode() !== null && this.form.dirty ? UNSAVED_CHANGES : null;
  }


  readonly developmentId = input.required<string>();
  /** Casa de los gastos; nula para los generales y de obras comunes del desarrollo. */
  readonly unitId = input<string | null>(null);
  readonly expenses = input.required<ExpenseResponse[]>();
  /** Etapas a las que se puede imputar un gasto: las de la casa, o las comunes. */
  readonly stages = input.required<ExpenseStageOption[]>();
  readonly title = input('Gastos');
  /** Cómo se llama un gasto sin etapa: "General". */
  readonly generalLabel = input('General');
  /** Cuántos mostrar antes de "Ver todos"; nulo, todos. */
  readonly limit = input<number | null>(null);
  /** Falso donde los gastos se cargan en la franja de arriba. */
  readonly canAdd = input(true);
  /** Dónde va cada gasto, en el desarrollo; nulo: la columna es la etapa. */
  readonly placeOf = input<((expense: ExpenseResponse) => string) | null>(null);
  /** Las etapas para corregir un gasto según su casa (nula: obras comunes o general). */
  readonly choicesFor = input<((unitId: string | null) => ExpenseStageChoices) | null>(null);
  /** Se cargó, corrigió o borró un gasto: quien muestra el panel lo recarga. */
  readonly changed = output<void>();

  private readonly dashboard = inject(DashboardService);
  private readonly formBuilder = inject(FormBuilder);

  protected readonly today = todayLocal();
  /** Qué formulario está abierto: el de cargar, el de una fila (su id) o ninguno. */
  protected readonly mode = signal<'add' | string | null>(null);
  protected readonly expanded = signal(false);
  private readonly editingUnitId = signal<string | null>(null);

  protected readonly formChoices = computed<ExpenseStageChoices>(() =>
    this.choicesFor()?.(this.editingUnitId()) ?? { stages: this.stages(), generalLabel: this.generalLabel() });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    incurredOn: [todayLocal(), Validators.required],
    stageId: [''],
    amount: ['', amountValidator],
    description: ['', Validators.maxLength(300)],
  });

  /** De más nuevo a más viejo; en un mismo día, el último cargado primero. */
  protected readonly sorted = computed(() => [...this.expenses()].sort((a, b) =>
    b.incurredOn.localeCompare(a.incurredOn) || b.createdAt.localeCompare(a.createdAt)));

  protected readonly shown = computed(() => {
    const limit = this.limit();
    return limit === null || this.expanded() ? this.sorted() : this.sorted().slice(0, limit);
  });
  protected readonly hidden = computed(() => this.sorted().length - this.shown().length);

  protected stageName(stageId: string | null): string {
    if (stageId === null) {
      return this.generalLabel();
    }
    return this.stages().find(stage => stage.stageId === stageId)?.name ?? 'Etapa';
  }

  protected startAdding(): void {
    this.editingUnitId.set(this.unitId());
    this.form.reset({ incurredOn: todayLocal(), stageId: '', amount: '', description: '' });
    this.error.set(null);
    this.mode.set('add');
  }

  protected startEditing(expense: ExpenseResponse): void {
    this.editingUnitId.set(expense.unitId);
    this.form.reset({
      incurredOn: expense.incurredOn,
      stageId: expense.stageId ?? '',
      amount: formatAmount(expense.amount),
      description: expense.description ?? '',
    });
    this.error.set(null);
    this.mode.set(expense.id);
  }

  protected close(): void {
    this.mode.set(null);
  }

  protected save(): void {
    const mode = this.mode();
    const value = this.form.getRawValue();
    const amount = parseAmount(value.amount);
    if (mode === null || this.form.invalid || amount === null || amount === 'invalid') {
      this.form.markAllAsTouched();
      return;
    }
    const data = {
      stageId: value.stageId || null,
      incurredOn: value.incurredOn,
      amount,
      description: value.description.trim() || null,
    };
    this.saving.set(true);
    this.error.set(null);
    const request = mode === 'add'
      ? this.dashboard.createExpense(this.developmentId(), { ...data, description: data.description ?? undefined, unitId: this.unitId() })
      : this.dashboard.updateExpense(mode, data);
    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.mode.set(null);
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(errorMessageOf(error, mode === 'add' ? 'No se pudo cargar el gasto.' : 'No se pudo guardar el gasto.'));
      },
    });
  }

  protected remove(expense: ExpenseResponse): void {
    if (!window.confirm('¿Borrar este gasto? Deja de sumar en el panel.')) {
      return;
    }
    this.error.set(null);
    this.dashboard.deleteExpense(expense.id).subscribe({
      next: () => this.changed.emit(),
      error: (error: unknown) => this.error.set(errorMessageOf(error, 'No se pudo borrar el gasto.')),
    });
  }
}
