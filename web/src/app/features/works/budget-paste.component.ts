import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { catchError, concatMap, from, map, of, toArray } from 'rxjs';
import { StageDashboardRow } from '../../core/api/api.models';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { MoneyPipe } from '../../shared/money/money.pipe';
import { parsePastedAmounts } from './economics-derivations';

interface PreviewRow {
  stage: StageDashboardRow;
  text: string | null;
  amount: number | null;
  error: string | null;
  changes: boolean;
}

/**
 * "Editar presupuesto": pegar desde Excel una columna de montos, una etapa por línea en el
 * orden de obra. Antes de guardar se ve etapa por etapa qué queda; una línea vacía no cambia
 * su etapa y una que no se entiende frena todo, para no guardar a medias un monto mal leído.
 * Cada etapa se guarda con su pedido de siempre, una tras otra; si alguna falla se dice cuál.
 */
@Component({
  selector: 'app-budget-paste',
  imports: [MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="sheet" aria-labelledby="paste-title" (close)="closed.emit()">
      <header class="head">
        <div>
          <h3 id="paste-title">Editar presupuesto</h3>
          <p class="muted">Pegá la columna de montos desde Excel: una etapa por línea, en este orden. Una línea vacía deja la etapa como está.</p>
        </div>
        <button type="button" class="ov-link" (click)="close()">Cerrar</button>
      </header>

      <label class="ov-eyebrow" for="paste-amounts">Montos en US$</label>
      <textarea id="paste-amounts" class="ov-input" rows="6" placeholder="7.000&#10;16.000&#10;30.000"
                [value]="text()" (input)="text.set($any($event.target).value)"></textarea>

      @if (mismatch(); as line) {
        <p class="warn" role="status">{{ line }}</p>
      }

      <table class="preview">
        <thead>
          <tr>
            <th scope="col">Etapa</th>
            <th scope="col" class="num">Hoy</th>
            <th scope="col" class="num">Queda</th>
          </tr>
        </thead>
        <tbody>
          @for (row of preview(); track row.stage.stageId) {
            <tr [class.changes]="row.changes" [class.invalid]="row.error !== null">
              <th scope="row">{{ row.stage.name }}</th>
              <td class="num">{{ row.stage.budgetAmount | money }}</td>
              <td class="num">
                @if (row.error; as error) {
                  <span class="warn">"{{ row.text }}": {{ error }}</span>
                } @else if (row.changes) {
                  {{ row.amount | money }}
                } @else {
                  <span class="muted">Sin cambio</span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>

      @if (failures().length > 0) {
        <div class="warn" role="alert">
          <p>{{ failures().length === 1 ? 'No se pudo guardar una etapa' : 'No se pudieron guardar ' + failures().length + ' etapas' }}; las demás quedaron guardadas:</p>
          <ul>
            @for (failure of failures(); track failure) {
              <li>{{ failure }}</li>
            }
          </ul>
        </div>
      }

      <div class="actions">
        <button class="ov-btn ov-btn--primary" type="button" [disabled]="!canApply()" (click)="apply()">
          {{ saving() ? 'Guardando…' : changeCount() === 0 ? 'Guardar' : 'Guardar ' + changeCount() + (changeCount() === 1 ? ' etapa' : ' etapas') }}
        </button>
        <button class="ov-link" type="button" (click)="close()">Cancelar</button>
      </div>
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

    .sheet[open] {
      display: grid;
      gap: var(--ov-space-4);
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

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    textarea {
      font-variant-numeric: tabular-nums;
    }

    .preview {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--ov-text-sm);
    }

    th,
    td {
      padding: var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
      text-align: left;
      font-weight: var(--ov-weight-regular);
    }

    thead th {
      border-top: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }

    tr.changes td:last-child {
      font-weight: var(--ov-weight-semibold);
    }

    .warn {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
    }

    .warn ul {
      margin: var(--ov-space-1) 0 0;
      padding-left: var(--ov-space-5);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }
  `,
})
export class BudgetPasteComponent {
  /** Las etapas de la casa, en orden de obra. */
  readonly stages = input.required<StageDashboardRow[]>();
  /** Se guardó al menos una etapa: hay que recargar el panel. */
  readonly applied = output<void>();
  readonly closed = output<void>();

  private readonly stagesService = inject(StagesService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected readonly text = signal('');
  protected readonly saving = signal(false);
  protected readonly failures = signal<string[]>([]);

  private readonly pasted = computed(() => parsePastedAmounts(this.text()));

  protected readonly preview = computed<PreviewRow[]>(() => {
    const pasted = this.pasted();
    return this.stages().map((stage, index) => {
      const line = pasted[index];
      const amount = line?.amount ?? null;
      return {
        stage,
        text: line?.text ?? null,
        amount,
        error: line?.error ?? null,
        changes: amount !== null && amount !== stage.budgetAmount,
      };
    });
  });

  protected readonly changeCount = computed(() => this.preview().filter(row => row.changes).length);

  protected readonly mismatch = computed(() => {
    const lines = this.pasted().length;
    const stages = this.stages().length;
    if (lines === 0 || lines === stages) return null;
    return lines > stages
      ? `Pegaste ${lines} líneas y la casa tiene ${stages} etapas: ${lines - stages === 1 ? 'la última no se usa' : 'las últimas ' + (lines - stages) + ' no se usan'}.`
      : `Pegaste ${lines} ${lines === 1 ? 'línea' : 'líneas'} para ${stages} etapas: ${stages - lines === 1 ? 'la última queda como está' : 'las últimas ' + (stages - lines) + ' quedan como están'}.`;
  });

  protected readonly canApply = computed(() =>
    !this.saving() && this.changeCount() > 0 && this.preview().every(row => row.error === null));

  constructor() {
    // jsdom no tiene showModal: el contenido igual está, y en el navegador abre modal.
    afterNextRender(() => this.dialog().nativeElement.showModal?.());
  }

  protected close(): void {
    const dialog = this.dialog().nativeElement;
    if (dialog.open) {
      dialog.close();
    } else {
      this.closed.emit();
    }
  }

  protected apply(): void {
    const rows = this.preview().filter(row => row.changes);
    this.saving.set(true);
    this.failures.set([]);
    from(rows).pipe(
      concatMap(row => this.stagesService.updatePlan(row.stage.stageId, { budgetAmount: row.amount! }).pipe(
        map(() => null),
        catchError((error: unknown) => of(`${row.stage.name}: ${errorMessageOf(error, 'no se pudo guardar')}`)),
      )),
      toArray(),
    ).subscribe(results => {
      this.saving.set(false);
      const failures = results.filter((result): result is string => result !== null);
      if (failures.length < rows.length) {
        this.applied.emit();
      }
      if (failures.length === 0) {
        this.close();
      } else {
        this.failures.set(failures);
      }
    });
  }
}
