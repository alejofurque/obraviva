import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MoneyPipe } from '../../shared/money/money.pipe';
import { StageDashboardRow } from '../../core/api/api.models';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { formatAmount, parseAmount } from './amounts';
import { SpendState, spendState } from './economics-derivations';

/**
 * "Por etapa": presupuesto, gastado, una barra de gasto con la marca del avance y el estado
 * en palabras, en orden de obra. La usan el Económico de una casa
 * y las obras comunes del desarrollo.
 *
 * <p>El presupuesto se edita en su celda: al tocarlo se vuelve campo, se guarda con Enter
 * o al salir del campo y se cancela con Escape. Vacío lo borra: queda "sin presupuesto",
 * no en cero. Las alertas van en ámbar, nunca en rojo, y siempre con su texto.
 */
@Component({
  selector: 'app-stage-budget-table',
  imports: [MoneyPipe, DecimalPipe, ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as message) {
      <p class="ov-error" role="alert">{{ message }}</p>
    }
    <div class="wrap">
    <table class="stage-table">
      <thead>
        <tr>
          <th scope="col">Etapa</th>
          <th scope="col" class="num">Presupuesto</th>
          <th scope="col" class="num">Gastado</th>
          <th scope="col" class="versus">Gasto vs. avance</th>
          <th scope="col">Estado</th>
        </tr>
      </thead>
      <tbody>
        @for (stage of sorted(); track stage.stageId) {
          @let verdict = stateOf(stage);
          <tr [attr.data-state]="verdict.state">
            <th scope="row" class="name">{{ stage.name }}</th>
            <td class="num budget">
              @if (editing() === stage.stageId) {
                <form [formGroup]="budgetForm" (ngSubmit)="save(stage)" novalidate>
                  <label class="ov-visually-hidden" [for]="'budget-' + stage.stageId">Presupuesto de {{ stage.name }}</label>
                  <input #budgetInput [id]="'budget-' + stage.stageId" class="ov-input" inputmode="decimal"
                         formControlName="amount" placeholder="Monto en US$"
                         (keydown.escape)="cancel()" (blur)="save(stage)" />
                </form>
              } @else {
                <button type="button" class="budget-cell" (click)="edit(stage)"
                        [attr.aria-label]="(stage.budgetAmount === null ? 'Cargar el presupuesto de ' : 'Cambiar el presupuesto de ') + stage.name">
                  {{ stage.budgetAmount === null ? 'Cargar' : (stage.budgetAmount | money) }}
                </button>
              }
            </td>
            <td class="num spent">{{ stage.spentAmount === 0 ? '—' : (stage.spentAmount | money) }}</td>
            <td class="versus">
              @if (stage.budgetUsedPercent !== null) {
                <div class="bar" role="img"
                     [attr.aria-label]="(stage.budgetUsedPercent | number: '1.0-0') + '% gastado, ' + stage.progressPercent + '% de avance'">
                  <span class="spent-bar" [style.width.%]="min(stage.budgetUsedPercent, 100)"></span>
                  <span class="mark" [style.left.%]="stage.progressPercent"></span>
                </div>
                <p class="bar-text" aria-hidden="true">
                  <span>{{ stage.budgetUsedPercent | number: '1.0-0' }}% gastado</span><span>{{ stage.progressPercent }}% avance</span>
                </p>
              } @else {
                <p class="bar-text">{{ stage.progressPercent }}% avance</p>
              }
            </td>
            <td class="state"><span class="chip">{{ verdict.label }}</span></td>
          </tr>
        }
      </tbody>
    </table>
    </div>
    <p class="foot">La barra es lo gastado de cada etapa; la marca, cuánto avanzó. Si la barra pasa mucho la marca, la etapa gasta más rápido de lo que avanza.</p>
  `,
  styles: `
    :host {
      display: block;
    }

    .wrap {
      overflow-x: auto;
    }

    .stage-table {
      width: 100%;
      min-width: 44rem;
      border-collapse: collapse;
      font-size: var(--ov-text-sm);
    }

    th,
    td {
      padding: var(--ov-space-3) var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      text-align: left;
      vertical-align: middle;
    }

    th:first-child,
    td:first-child {
      padding-left: var(--ov-space-5);
    }

    thead th {
      border-top: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .name {
      font-weight: var(--ov-weight-medium);
    }

    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    .budget-cell {
      min-height: 2.25rem;
      padding: 0.125rem 0;
      border: 0;
      border-bottom: 1px dashed var(--ov-line-strong);
      background: none;
      color: var(--ov-ink);
      font: inherit;
      font-variant-numeric: tabular-nums;
      cursor: pointer;
    }

    tr[data-state='unbudgeted'] .budget-cell {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .budget .ov-input {
      width: 9rem;
      text-align: right;
    }

    .versus {
      width: 13rem;
    }

    .bar {
      position: relative;
      height: 0.625rem;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .spent-bar {
      position: absolute;
      inset: 0 auto 0 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent);
    }

    tr[data-state='watch'] .spent-bar,
    tr[data-state='over'] .spent-bar {
      background: var(--ov-status-delayed);
    }

    .mark {
      position: absolute;
      top: -0.1875rem;
      width: 2px;
      height: 1rem;
      margin-left: -1px;
      background: var(--ov-ink);
    }

    .bar-text {
      display: flex;
      justify-content: space-between;
      margin-top: var(--ov-space-1);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .chip {
      padding: 0.2rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      white-space: nowrap;
    }

    tr[data-state='ok'] .chip {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    tr[data-state='watch'] .chip,
    tr[data-state='over'] .chip {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .foot {
      padding: var(--ov-space-3) var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    /* Teléfono: cada etapa en un bloque, sin scroll horizontal. */
    @media (max-width: 40rem) {
      .stage-table { min-width: 0; }
      thead { display: none; }

      tr {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas: 'name state' 'budget spent' 'versus versus';
        gap: var(--ov-space-2) var(--ov-space-3);
        padding: var(--ov-space-3) var(--ov-space-5);
        border-top: 1px solid var(--ov-line);
      }

      th, td, th:first-child, td:first-child { padding: 0; border: 0; }
      .name { grid-area: name; }
      .state { grid-area: state; }
      .budget { grid-area: budget; text-align: left; }
      .spent { grid-area: spent; }
      .spent::before { content: 'Gastado '; color: var(--ov-ink-muted); }
      .versus { grid-area: versus; width: auto; }
    }
  `,
})
export class StageBudgetTableComponent {

  readonly stages = input.required<StageDashboardRow[]>();
  /** Se guardó un presupuesto: quien muestra la tabla recarga el panel. */
  readonly changed = output<void>();

  private readonly stagesService = inject(StagesService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly budgetInput = viewChild<ElementRef<HTMLInputElement>>('budgetInput');

  protected readonly editing = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private saving = false;

  /** Presupuesto de la etapa abierta: uno solo, porque se edita una etapa por vez. */
  protected readonly budgetForm = this.formBuilder.nonNullable.group({ amount: [''] });

  protected sorted(): StageDashboardRow[] {
    return [...this.stages()].sort((a, b) => a.orderIndex - b.orderIndex);
  }

  protected stateOf(stage: StageDashboardRow): { state: SpendState; label: string } {
    return spendState(stage.trafficLight, stage.spentAmount);
  }

  protected min(a: number, b: number): number {
    return Math.min(a, b);
  }

  /** Abre la celda con el monto actual, escrito como en Argentina; vacío si no tiene. */
  protected edit(stage: StageDashboardRow): void {
    this.budgetForm.reset({ amount: stage.budgetAmount === null ? '' : formatAmount(stage.budgetAmount) });
    this.error.set(null);
    this.editing.set(stage.stageId);
    queueMicrotask(() => this.budgetInput()?.nativeElement.focus());
  }

  protected cancel(): void {
    this.editing.set(null);
  }

  /**
   * Guarda con Enter o al salir del campo. Si el monto no cambió no manda nada; si es
   * inválido lo explica y deja la celda abierta. Vacío borra el presupuesto.
   */
  protected save(stage: StageDashboardRow): void {
    if (this.editing() !== stage.stageId || this.saving) {
      return;
    }
    const amount = parseAmount(this.budgetForm.getRawValue().amount);
    if (amount === 'invalid') {
      this.error.set('El presupuesto tiene que ser un monto válido, como 21.000 o 21.000,50.');
      return;
    }
    if (amount === stage.budgetAmount) {
      this.editing.set(null);
      return;
    }
    this.saving = true;
    this.error.set(null);
    const changes = amount === null ? { clearBudget: true } : { budgetAmount: amount };
    this.stagesService.updatePlan(stage.stageId, changes).subscribe({
      next: () => {
        this.saving = false;
        this.editing.set(null);
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.saving = false;
        this.error.set(errorMessageOf(error, 'No se pudo guardar el presupuesto.'));
      },
    });
  }
}
