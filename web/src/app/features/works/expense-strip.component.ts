import { ChangeDetectionStrategy, Component, ElementRef, OnInit, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { ExpenseResponse } from '../../core/api/api.models';
import { DashboardService } from '../../core/api/dashboard.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { formatMoney } from '../../shared/money/money';
import { todayLocal } from '../../shared/time/local-date';
import { parseAmount } from './amounts';

export interface ExpenseStageChoice {
  stageId: string;
  name: string;
}

/**
 * Dónde va un gasto en el desarrollo: una casa (con sus etapas y "General de la casa"), las
 * obras comunes (con las suyas) o general del desarrollo (sin etapa).
 */
export interface ExpensePlace {
  key: string;
  label: string;
  unitId: string | null;
  /** Nulo: no se elige etapa (general del desarrollo). */
  stages: ExpenseStageChoice[] | null;
  /** Lo que dice la opción sin etapa; nulo si no se ofrece. */
  generalLabel: string | null;
}

/**
 * "Cargar gasto" como una franja: monto en US$, etapa, concepto y fecha (hoy). En el
 * desarrollo suma "Dónde". Guardar no la cierra: queda vacía para el siguiente, que es como
 * se cargan las facturas de una semana.
 */
@Component({
  selector: 'app-expense-strip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="strip" (submit)="$event.preventDefault(); save()" novalidate aria-label="Cargar gasto">
      <div class="ov-field amount">
        <label class="ov-eyebrow" for="strip-amount">Monto (US$)</label>
        <input #amountInput id="strip-amount" class="ov-input" inputmode="decimal" placeholder="4.800"
               [value]="amount()" (input)="amount.set($any($event.target).value)" />
      </div>
      @if (places().length > 1) {
        <div class="ov-field">
          <label class="ov-eyebrow" for="strip-place">Dónde</label>
          <select id="strip-place" class="ov-input" (change)="choosePlace($any($event.target).value)">
            @for (place of places(); track place.key) {
              <option [value]="place.key" [selected]="place.key === placeKey()">{{ place.label }}</option>
            }
          </select>
        </div>
      }
      @if (place().stages; as stages) {
        <div class="ov-field">
          <label class="ov-eyebrow" for="strip-stage">Etapa</label>
          <select id="strip-stage" class="ov-input" (change)="stageId.set($any($event.target).value)">
            @for (stage of stages; track stage.stageId) {
              <option [value]="stage.stageId" [selected]="stage.stageId === stageId()">{{ stage.name }}</option>
            }
            @if (place().generalLabel; as general) {
              <option value="" [selected]="stageId() === ''">{{ general }}</option>
            }
          </select>
        </div>
      }
      <div class="ov-field concept">
        <label class="ov-eyebrow" for="strip-concept">Concepto</label>
        <input id="strip-concept" class="ov-input" maxlength="300" placeholder="Por ejemplo: cables y tablero"
               [value]="concept()" (input)="concept.set($any($event.target).value)" />
      </div>
      <div class="ov-field">
        <label class="ov-eyebrow" for="strip-date">Fecha</label>
        <input id="strip-date" class="ov-input" type="date" [max]="today" [value]="date()" (change)="date.set($any($event.target).value)" />
      </div>
      <div class="actions">
        <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving()">{{ saving() ? 'Guardando…' : 'Guardar' }}</button>
        <button class="ov-link" type="button" (click)="closed.emit()">Cerrar</button>
      </div>
      @if (error(); as message) {
        <p class="ov-error wide" role="alert">{{ message }}</p>
      } @else if (last(); as line) {
        <p class="saved wide" role="status">{{ line }}</p>
      }
    </form>
  `,
  styles: `
    .strip {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) var(--ov-space-5);
      border: 2px solid var(--ov-ink);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .amount .ov-input {
      width: 9rem;
      font-size: var(--ov-text-md);
      font-weight: var(--ov-weight-semibold);
    }

    .concept {
      flex: 1 1 14rem;
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 2.75rem;
    }

    .wide {
      flex-basis: 100%;
    }

    .saved {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    @media (max-width: 40rem) {
      .ov-field,
      .amount .ov-input {
        width: 100%;
      }
    }
  `,
})
export class ExpenseStripComponent implements OnInit {
  readonly developmentId = input.required<string>();
  /** Dónde se puede cargar: en la casa, un único lugar (y no se muestra "Dónde"). */
  readonly places = input.required<ExpensePlace[]>();
  /** La etapa que se propone, por ejemplo la que está en curso. */
  readonly suggestedStageId = input<string | null>(null);
  readonly saved = output<ExpenseResponse>();
  readonly closed = output<void>();

  private readonly dashboard = inject(DashboardService);
  private readonly amountInput = viewChild<ElementRef<HTMLInputElement>>('amountInput');

  protected readonly today = todayLocal();
  protected readonly amount = signal('');
  protected readonly concept = signal('');
  protected readonly date = signal(this.today);
  protected readonly placeKey = signal('');
  protected readonly stageId = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly last = signal<string | null>(null);
  protected readonly place = computed(() =>
    this.places().find(candidate => candidate.key === this.placeKey()) ?? this.places()[0]);

  constructor() {
    afterNextRender(() => this.amountInput()?.nativeElement.focus());
  }

  ngOnInit(): void {
    this.choosePlace(this.places()[0]?.key ?? '');
  }

  protected choosePlace(key: string): void {
    this.placeKey.set(key);
    const stages = this.place()?.stages ?? null;
    const suggested = this.suggestedStageId();
    // Sin una etapa propuesta en ese lugar, mejor "General" que la primera etapa a ciegas.
    this.stageId.set(stages === null ? ''
      : stages.some(stage => stage.stageId === suggested) ? suggested!
        : this.place().generalLabel !== null ? '' : stages[0]?.stageId ?? '');
  }

  protected save(): void {
    const amount = parseAmount(this.amount());
    if (amount === null || amount === 'invalid' || amount <= 0) {
      this.error.set('El monto tiene que ser mayor que cero, escrito como 4.800 o 1.250,50.');
      return;
    }
    if (!this.date() || this.date() > this.today) {
      this.error.set('La fecha no puede ser futura.');
      return;
    }
    const place = this.place();
    const stageId = place.stages === null ? null : this.stageId() || null;
    this.saving.set(true);
    this.error.set(null);
    this.dashboard.createExpense(this.developmentId(), {
      amount,
      stageId,
      unitId: place.unitId,
      incurredOn: this.date(),
      description: this.concept().trim() || undefined,
    }).subscribe({
      next: expense => {
        this.saving.set(false);
        const where = place.stages?.find(stage => stage.stageId === stageId)?.name ?? place.generalLabel ?? place.label;
        this.last.set(`Cargado: ${formatMoney(amount)} en ${where}. Podés cargar el siguiente.`);
        this.amount.set('');
        this.concept.set('');
        this.saved.emit(expense);
        this.amountInput()?.nativeElement.focus();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo cargar el gasto.'));
      },
    });
  }
}
