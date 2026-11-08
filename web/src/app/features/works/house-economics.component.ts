import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { DashboardResponse, ExpenseResponse } from '../../core/api/api.models';
import { DashboardService } from '../../core/api/dashboard.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, warnOnUnload } from '../../shared/ui/leave-guard';
import { todayLocal } from '../../shared/time/local-date';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { BudgetPasteComponent } from './budget-paste.component';
import { curveChart, curveNote, houseKpis } from './economics-derivations';
import { EconomicsKpisComponent } from './economics-kpis.component';
import { ExpensePlace, ExpenseStripComponent } from './expense-strip.component';
import { ExpenseStageOption, ExpensesPanelComponent } from './expenses-panel.component';
import { HouseStore } from './house.store';
import { SpendingCurveComponent } from './spending-curve.component';
import { StageBudgetTableComponent } from './stage-budget-table.component';
import { WorkStore } from './work.store';

const GENERAL = 'General de la casa';

/**
 * Económico de una casa: presupuesto contra gasto, en dólares,
 * sólo para el dueño. De arriba abajo: la franja "Cargar gasto", cuatro indicadores, la curva
 * de gasto acumulado contra la prevista, la tabla por etapa con el presupuesto editable en la
 * celda y los últimos gastos. Las alertas van en ámbar y siempre con su texto.
 *
 * <p>Los números salen del servidor; acá sólo se leen. El costo final estimado es una regla de
 * tres explícita y lo dice.
 */
@Component({
  selector: 'app-house-economics',
  imports: [LoadingStateComponent, ErrorStateComponent, EconomicsKpisComponent, ExpenseStripComponent,
    SpendingCurveComponent, StageBudgetTableComponent, ExpensesPanelComponent, BudgetPasteComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page economics">
      @if (loading()) {
        <app-loading-state label="Cargando el económico de la casa…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (panel(); as data) {
        <header class="intro">
          <div>
            <h2>Económico de la casa</h2>
            <p class="ov-lead">Presupuesto contra gasto, en dólares. Sólo lo ves vos: ni el encargado ni el comprador.</p>
          </div>
          <div class="actions">
            @if (data.stages.length > 0) {
              <button class="ov-btn ov-btn--secondary" type="button" (click)="pasting.set(true)">Editar presupuesto</button>
            }
            <button class="ov-btn ov-btn--primary" type="button" [attr.aria-expanded]="adding()" (click)="adding.set(true)">Cargar gasto</button>
          </div>
        </header>

        @if (adding()) {
          <app-expense-strip [developmentId]="data.developmentId" [places]="places()" [suggestedStageId]="currentStageId()"
                             (saved)="refresh()" (closed)="adding.set(false)" />
        }

        <app-economics-kpis [kpis]="kpis()" />

        <app-spending-curve [chart]="chart()" [note]="note()" />

        <section class="ov-card stages" aria-labelledby="stages-title">
          <div class="stages-head">
            <h3 id="stages-title">Por etapa</h3>
            @if (data.stages.length > 0) {
              <p class="muted">Tocá un presupuesto para cambiarlo</p>
            }
          </div>
          @if (data.stages.length === 0) {
            <p class="muted empty">La casa todavía no tiene etapas. Cargalas en la pestaña Etapas.</p>
          } @else {
            <app-stage-budget-table [stages]="data.stages" (changed)="refresh()" />
          }
        </section>

        <app-expenses-panel
          [developmentId]="data.developmentId"
          [unitId]="unitId()"
          [expenses]="expenses()"
          [stages]="stageOptions()"
          [generalLabel]="general"
          [limit]="5"
          [canAdd]="false"
          title="Últimos gastos"
          (changed)="refresh()" />

        @if (pasting()) {
          <app-budget-paste [stages]="sortedStages()" (applied)="refresh()" (closed)="pasting.set(false)" />
        }
      }
    </div>
  `,
  styles: `
    .economics {
      display: grid;
      gap: var(--ov-space-6);
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .intro .ov-lead {
      margin-top: var(--ov-space-2);
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-3);
    }

    .stages {
      padding: 0;
      overflow: hidden;
    }

    .stages-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
      padding: var(--ov-space-5) var(--ov-space-5) var(--ov-space-3);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .empty {
      padding: 0 var(--ov-space-5) var(--ov-space-5);
    }
  `,
})
export class HouseEconomicsComponent implements OnInit, LeaveGuarded {
  private readonly expensesPanel = viewChild(ExpensesPanelComponent);

  private readonly store = inject(WorkStore);
  private readonly house = inject(HouseStore);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly general = GENERAL;
  protected readonly unitId = this.house.unitId;
  protected readonly panel = signal<DashboardResponse | null>(null);
  protected readonly expenses = signal<ExpenseResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly pasting = signal(false);

  protected readonly sortedStages = computed(() =>
    [...(this.panel()?.stages ?? [])].sort((a, b) => a.orderIndex - b.orderIndex));
  protected readonly stageOptions = computed<ExpenseStageOption[]>(() =>
    this.sortedStages().map(stage => ({ stageId: stage.stageId, name: stage.name })));
  protected readonly places = computed<ExpensePlace[]>(() => [{
    key: 'house', label: this.house.unit()?.code ?? 'La casa', unitId: this.unitId(),
    stages: this.stageOptions(), generalLabel: GENERAL,
  }]);
  /** Se propone la primera etapa abierta que se está trabajando, o la primera pendiente. */
  protected readonly currentStageId = computed(() => {
    const stages = this.sortedStages();
    return (stages.find(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED')
      ?? stages.find(stage => stage.status === 'PENDING'))?.stageId ?? null;
  });

  private readonly month = todayLocal().slice(0, 7);
  protected readonly kpis = computed(() => {
    const panel = this.panel();
    return panel ? houseKpis(panel, this.expenses(), this.month) : [];
  });
  protected readonly chart = computed(() => {
    const panel = this.panel();
    return curveChart(panel?.plannedCurve ?? [], panel?.spendingCurve ?? [], this.month);
  });
  protected readonly note = computed(() => {
    const panel = this.panel();
    return panel ? curveNote(panel) : null;
  });

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.expensesPanel()?.leaveWarning() ?? null;
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.fetch(() => this.loading.set(false));
  }

  /** Recarga sin volver al estado de carga: la franja queda abierta y la página en su lugar. */
  protected refresh(): void {
    this.fetch(() => {});
  }

  private fetch(done: () => void): void {
    const developmentId = this.store.development()?.id ?? '';
    forkJoin({
      panel: this.dashboard.dashboard(developmentId, this.unitId()),
      expenses: this.dashboard.expenses(developmentId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ panel, expenses }) => {
          this.panel.set(panel);
          this.expenses.set(expenses.filter(expense => expense.unitId === this.unitId()));
          done();
        },
        error: (error: unknown) => {
          done();
          this.loadError.set(errorMessageOf(error, 'No se pudo cargar el económico de la casa.'));
        },
      });
  }
}
