import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { DashboardResponse, ExpenseResponse, StageOption } from '../../core/api/api.models';
import { DashboardService } from '../../core/api/dashboard.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, warnOnUnload } from '../../shared/ui/leave-guard';
import { todayLocal } from '../../shared/time/local-date';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { DevelopmentBudgetTableComponent } from './development-budget-table.component';
import { developmentKpis } from './economics-derivations';
import { EconomicsKpisComponent } from './economics-kpis.component';
import { ExpensePlace, ExpenseStripComponent } from './expense-strip.component';
import { ExpenseStageChoices, ExpenseStageOption, ExpensesPanelComponent } from './expenses-panel.component';
import { MonthlySpendingComponent } from './monthly-spending.component';
import { StageBudgetTableComponent } from './stage-budget-table.component';
import { WorkStore } from './work.store';

const HOUSE_GENERAL = 'General de la casa';
const DEVELOPMENT_GENERAL = 'General del desarrollo';
const COMMONS = 'Obras comunes';

/**
 * Económico del desarrollo: todas las casas y las obras
 * comunes, en dólares, sólo para el dueño. De arriba abajo: la franja "Cargar gasto" con "Dónde"
 * (una casa, las obras comunes o general del desarrollo), cuatro indicadores, "Por casa" con el
 * costo final estimado de cada una, el gasto por mes, los últimos gastos y las etapas de las obras
 * comunes con su presupuesto editable.
 *
 * <p>El costo final estimado del desarrollo es la suma de sus partes, no lo gastado dividido por
 * el avance del desarrollo: ese avance promedia las casas y deja afuera lo común.
 */
@Component({
  selector: 'app-work-economics',
  imports: [RouterLink, LoadingStateComponent, ErrorStateComponent, EconomicsKpisComponent, ExpenseStripComponent,
    DevelopmentBudgetTableComponent, MonthlySpendingComponent, ExpensesPanelComponent, StageBudgetTableComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page economics">
      @if (loading()) {
        <app-loading-state label="Cargando el económico del desarrollo…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (panel(); as data) {
        <header class="intro">
          <div>
            <h2>Económico</h2>
            <p class="ov-lead">{{ lead() }} Sólo lo ves vos: ni los encargados ni los compradores.</p>
          </div>
          <div class="actions">
            <a class="ov-btn ov-btn--secondary" [routerLink]="['/obras', data.developmentId, 'economico', 'reportes']">Reportes mensuales</a>
            <button class="ov-btn ov-btn--primary" type="button" [attr.aria-expanded]="adding()" (click)="adding.set(true)">Cargar gasto</button>
          </div>
        </header>

        @if (adding()) {
          <app-expense-strip [developmentId]="data.developmentId" [places]="places()"
                             (saved)="refresh()" (closed)="adding.set(false)" />
        }

        <app-economics-kpis [kpis]="kpis()" />

        <section class="ov-card table-card" aria-labelledby="houses-title">
          <div class="card-head">
            <h3 id="houses-title">Por casa</h3>
            @if (data.units.length > 0) {
              <p class="muted">Tocá una fila para ver su económico</p>
            }
          </div>
          @if (data.units.length === 0 && !data.commonWorks) {
            <p class="muted empty">El desarrollo todavía no tiene casas.</p>
          } @else {
            <app-development-budget-table [developmentId]="data.developmentId" [houses]="data.units"
                                          [commonWorks]="data.commonWorks" (commons)="showCommons()" />
          }
        </section>

        <div class="pair">
          <app-monthly-spending [points]="data.spendingCurve" [currentMonth]="month" />
          <div class="ov-card expenses-card">
            <app-expenses-panel
              [developmentId]="data.developmentId"
              [expenses]="expenses()"
              [stages]="commonOptions()"
              [generalLabel]="developmentGeneral"
              [placeOf]="placeOf"
              [choicesFor]="choicesFor"
              [limit]="5"
              [canAdd]="false"
              title="Últimos gastos"
              (changed)="refresh()" />
          </div>
        </div>

        @if (data.stages.length > 0) {
          <section #commons class="ov-card table-card" aria-labelledby="commons-title" tabindex="-1">
            <div class="card-head">
              <h3 id="commons-title">Obras comunes, por etapa</h3>
              <p class="muted">Tocá un presupuesto para cambiarlo</p>
            </div>
            <app-stage-budget-table [stages]="data.stages" (changed)="refresh()" />
          </section>
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

    .table-card {
      padding: 0;
      overflow: hidden;
    }

    .card-head {
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

    .pair {
      display: grid;
      gap: var(--ov-space-6);
      align-items: start;
    }

    .expenses-card {
      min-width: 0;
      padding: var(--ov-space-5);
    }

    @media (min-width: 72rem) {
      .pair {
        grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
      }
    }
  `,
})
export class WorkEconomicsComponent implements OnInit, LeaveGuarded {
  private readonly expensesPanel = viewChild(ExpensesPanelComponent);

  private readonly store = inject(WorkStore);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly commonsSection = viewChild<ElementRef<HTMLElement>>('commons');

  protected readonly month = todayLocal().slice(0, 7);
  protected readonly developmentGeneral = DEVELOPMENT_GENERAL;
  protected readonly panel = signal<DashboardResponse | null>(null);
  protected readonly expenses = signal<ExpenseResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly adding = signal(false);

  protected readonly kpis = computed(() => {
    const panel = this.panel();
    return panel ? developmentKpis(panel) : [];
  });

  protected readonly lead = computed(() => {
    const houses = this.panel()?.units.length ?? 0;
    const what = houses === 1 ? 'La casa' : `Las ${houses} casas`;
    return this.panel()?.commonWorks ? `${what} y las obras comunes, en dólares.` : `${what}, en dólares.`;
  });

  private readonly stagesByUnit = computed(() => {
    const byUnit = new Map<string | null, ExpenseStageOption[]>();
    for (const stage of this.panel()?.stageOptions ?? []) {
      byUnit.set(stage.unitId, [...(byUnit.get(stage.unitId) ?? []), { stageId: stage.stageId, name: stage.name }]);
    }
    return byUnit;
  });
  private readonly stageById = computed(() =>
    new Map<string, StageOption>((this.panel()?.stageOptions ?? []).map(stage => [stage.stageId, stage])));
  private readonly codes = computed(() =>
    new Map((this.panel()?.units ?? []).map(unit => [unit.unitId, unit.code])));

  protected readonly commonOptions = computed(() => this.stagesByUnit().get(null) ?? []);

  /** Dónde se puede cargar: cada casa, las obras comunes (si tienen etapas) y general del desarrollo. */
  protected readonly places = computed<ExpensePlace[]>(() => {
    const units = this.panel()?.units ?? [];
    const commons = this.commonOptions();
    return [
      ...units.map(unit => ({
        key: unit.unitId!, label: unit.code!, unitId: unit.unitId,
        stages: this.stagesByUnit().get(unit.unitId) ?? [], generalLabel: HOUSE_GENERAL,
      })),
      ...(commons.length > 0 ? [{ key: 'comunes', label: COMMONS, unitId: null, stages: commons, generalLabel: null }] : []),
      { key: 'general', label: DEVELOPMENT_GENERAL, unitId: null, stages: null, generalLabel: null },
    ];
  });

  /** "Casa 1 · Estructura", "Casa 1 · General", "Obras comunes · Calles" o "General del desarrollo". */
  protected readonly placeOf = (expense: ExpenseResponse): string => {
    const stage = expense.stageId ? this.stageById().get(expense.stageId)?.name ?? 'Etapa' : null;
    if (expense.unitId === null) {
      return stage === null ? DEVELOPMENT_GENERAL : `${COMMONS} · ${stage}`;
    }
    return `${this.codes().get(expense.unitId) ?? 'Casa'} · ${stage ?? 'General'}`;
  };

  protected readonly choicesFor = (unitId: string | null): ExpenseStageChoices => ({
    stages: this.stagesByUnit().get(unitId) ?? [],
    generalLabel: unitId === null ? DEVELOPMENT_GENERAL : HOUSE_GENERAL,
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

  protected showCommons(): void {
    const section = this.commonsSection()?.nativeElement;
    section?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    section?.focus({ preventScroll: true });
  }

  private fetch(done: () => void): void {
    const developmentId = this.store.development()?.id ?? '';
    forkJoin({
      panel: this.dashboard.dashboard(developmentId),
      expenses: this.dashboard.expenses(developmentId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ panel, expenses }) => {
          this.panel.set(panel);
          this.expenses.set(expenses);
          done();
        },
        error: (error: unknown) => {
          done();
          this.loadError.set(errorMessageOf(error, 'No se pudo cargar el económico del desarrollo.'));
        },
      });
  }
}
