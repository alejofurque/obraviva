import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import {
  ActivityItem as FeedItem, AlertStage, Capture3d, DevelopmentOverview, HouseCard, PendingAudio, ProgressEntry, SetupStep, Unit,
} from '../../core/api/api.models';
import { ActivityService } from '../../core/api/activity.service';
import { DashboardService } from '../../core/api/dashboard.service';
import { DevelopmentsService } from '../../core/api/developments.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { ActivityLineComponent } from './activity-line.component';
import { HouseCardComponent } from './house-card.component';
import { compactMoney, deliveryLine, reviewItems, shortYearMonth } from './overview-derivations';
import { developmentStepPath, firstMissingData } from './work-labels';
import { sortHouses } from './work-sections';
import { WorkStore } from './work.store';

/** Un momento de la obra: un avance publicado o un recorrido 3D. */
export interface ActivityItem {
  kind: 'ENTRY' | 'CAPTURE';
  id: string;
  date: string;
  title: string;
  detail: string | null;
  audio: boolean;
}

/**
 * Avances y recorridos en una sola línea, del más nuevo al más viejo. Es lo que el
 * comprador ve como historia: el recorrido 3D es un momento más de la obra, no un anexo.
 */
export function activityOf(
  entries: ProgressEntry[],
  captures: Capture3d[],
  stageName: (stageId: string | null) => string | null,
): ActivityItem[] {
  const items: ActivityItem[] = [
    ...entries.filter(entry => entry.current).map(entry => ({
      kind: 'ENTRY' as const,
      id: entry.id,
      date: entry.entryDate,
      title: stageName(entry.stageId) ?? 'General de la obra',
      detail: entry.description,
      audio: entry.source === 'AUDIO',
    })),
    ...captures.map(capture => ({
      kind: 'CAPTURE' as const,
      id: capture.id,
      date: capture.captureDate,
      title: 'Recorrido 3D' + (capture.zone ? ' · ' + capture.zone : ''),
      detail: capture.description,
      audio: false,
    })),
  ];
  return items.sort((a, b) => b.date.localeCompare(a.date));
}

/** Cuántas casas hay en cada punto de su obra, para leer el barrio de un vistazo. */
export function houseBuckets(units: Pick<Unit, 'progressPercent' | 'stageCount'>[]): {
  notStarted: number; inProgress: number; finished: number;
} {
  let notStarted = 0;
  let inProgress = 0;
  let finished = 0;
  for (const unit of units) {
    const percent = unit.progressPercent ?? 0;
    if (!unit.stageCount || percent === 0) {
      notStarted++;
    } else if (percent >= 100) {
      finished++;
    } else {
      inProgress++;
    }
  }
  return { notStarted, inProgress, finished };
}

/**
 * Resumen del desarrollo para quien lo construye, en una pantalla: los indicadores arriba
 * (avance, casas en obra, venta, gasto y entrega), las casas como tarjetas (foto, venta,
 * avance, etapa, lo último que pasó y la alerta de gasto) y, a la derecha, lo que espera al
 * dueño ("Para revisar": audios y etapas en alerta) y las últimas novedades. Si faltan
 * datos, una sola línea lleva a completarlos. Los datos del barrio (descripción, ubicación,
 * amenities) se ven y se editan en "Editar datos".
 *
 * <p>El encargado ve los indicadores y las casas, sin venta, gasto ni lo del costado, que son
 * del dueño.
 */
@Component({
  selector: 'app-work-summary',
  imports: [RouterLink, MatIconModule, HouseCardComponent, ActivityLineComponent, LoadingStateComponent, ErrorStateComponent,
    EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page summary">
      @if (loading()) {
        <app-loading-state label="Cargando el resumen…" />
      } @else if (errorMessage(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (overview(); as data) {
        @if (missingData(); as path) {
          <p class="missing muted">
            Faltan algunos datos por completar · <a class="ov-link" [routerLink]="link(path)">Ver</a>
          </p>
        }

        <section class="kpis" aria-label="Indicadores">
          @if (store.development(); as work) {
            <div class="ov-card kpi">
              <!-- El encargado ve sólo sus casas: el número es el promedio de esas, no el del desarrollo. -->
              <p class="label">{{ store.isOwner() ? 'Avance del desarrollo' : 'Promedio de tus casas' }}</p>
              <p class="value ov-num">{{ work.overallProgressPercent }}<span class="unit">%</span></p>
              <div class="ov-progress" aria-hidden="true"><span [style.width.%]="work.overallProgressPercent"></span></div>
              @if (store.isOwner()) {
                <p class="sub">{{ units().length === 1 ? 'Una casa' : 'Promedio de las ' + units().length + ' casas' }}</p>
              } @else {
                <p class="sub">{{ units().length === 1 ? 'La casa que tenés asignada' : 'Las ' + units().length + ' casas que tenés asignadas' }}</p>
              }
            </div>
          }
          <a class="ov-card kpi" [routerLink]="link('casas')">
            <p class="label">Casas en obra</p>
            <p class="value ov-num">{{ buckets().inProgress }} <span class="unit">de {{ units().length }}</span></p>
            <p class="sub">{{ buckets().finished }} {{ buckets().finished === 1 ? 'terminada' : 'terminadas' }} · {{ buckets().notStarted }} sin empezar</p>
          </a>
          @if (data.commercial; as sales) {
            <a class="ov-card kpi" [routerLink]="link('casas')">
              <p class="label">Vendidas</p>
              <p class="value ov-num">{{ sales.sold }} <span class="unit">de {{ sales.units }}</span></p>
              <p class="sub">{{ sales.reserved }} {{ sales.reserved === 1 ? 'reservada' : 'reservadas' }} · {{ sales.available }} {{ sales.available === 1 ? 'disponible' : 'disponibles' }}</p>
            </a>
          }
          @if (data.economic; as economic) {
            <a class="ov-card kpi" [routerLink]="link('economico')">
              <p class="label">Gasto</p>
              <p class="value ov-num">{{ money(economic.totalSpent) }}</p>
              @if (economic.budgetUsedPercent !== null) {
                <div class="ov-progress muted-bar" aria-hidden="true"><span [style.width.%]="economic.budgetUsedPercent"></span></div>
                <p class="sub ov-num">{{ round(economic.budgetUsedPercent) }}% de {{ money(economic.totalBudget) }} presupuestados</p>
              } @else {
                <p class="sub">Sin presupuesto cargado</p>
              }
            </a>
          }
          <div class="ov-card kpi">
            <p class="label">Entrega estimada</p>
            <p class="value">{{ deliveryShort() ?? 'Sin definir' }}</p>
            <p class="sub">{{ deliveryNote() }}</p>
          </div>
        </section>

        <div class="columns">
          <section class="houses" aria-labelledby="houses-title">
            <div class="section-head">
              <h2 id="houses-title">Casas</h2>
              <a class="ov-link" [routerLink]="link('casas')">Ver todas</a>
            </div>
            @if (units().length === 0) {
              <app-empty-state title="Todavía no hay casas"
                [message]="store.isOwner() ? 'Cargá las casas del desarrollo: por ejemplo, diez casas de una vez con sus etapas estándar.' : 'El responsable todavía no cargó las casas.'">
                @if (store.isOwner()) {
                  <a class="ov-btn ov-btn--primary" [routerLink]="link('casas')" [queryParams]="{ nueva: 1 }">Cargar casas</a>
                }
              </app-empty-state>
            } @else {
              <ul class="grid">
                @for (unit of units(); track unit.id) {
                  <li><app-house-card [unit]="unit" [owner]="store.isOwner()" [card]="cardOf(unit.id)" /></li>
                }
              </ul>
            }
          </section>

          @if (store.isOwner()) {
            <aside class="side" aria-label="Para el responsable">
              <section class="ov-card panel" aria-labelledby="review-title">
                <div class="panel-head">
                  <h2 id="review-title">Para revisar</h2>
                  @if (review().length > 0) {
                    <span class="count ov-num">{{ review().length }}</span>
                  }
                </div>
                @for (item of review(); track item.link.join('/')) {
                  <div class="review" [attr.data-kind]="item.kind">
                    <span class="icon" aria-hidden="true"><mat-icon [svgIcon]="item.kind === 'audio' ? 'mic' : 'warning'" /></span>
                    <div class="review-text">
                      <p class="review-title">{{ item.title }}</p>
                      <p class="sub">{{ item.detail }}</p>
                    </div>
                    <a class="ov-link" [routerLink]="item.link">{{ item.action }}</a>
                  </div>
                } @empty {
                  <p class="sub empty">Nada para revisar: ni audios esperando ni etapas en alerta.</p>
                }
              </section>

              <section class="ov-card panel" aria-labelledby="news-title">
                <div class="panel-head">
                  <h2 id="news-title">Novedades</h2>
                  <a class="ov-link" [routerLink]="link('novedades')">Ver todas</a>
                </div>
                @for (item of news(); track item.id) {
                  <app-activity-line [item]="item" />
                } @empty {
                  <p class="sub empty">Todavía no hay novedades.</p>
                }
              </section>
            </aside>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .summary {
      display: grid;
      gap: var(--ov-space-6);
      padding-top: var(--ov-space-6);
    }

    h2 {
      font-size: var(--ov-text-lg);
    }

    .muted,
    .sub {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .missing {
      margin-bottom: calc(var(--ov-space-3) * -1);
    }

    .kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(11.5rem, 1fr));
      gap: var(--ov-space-3);
    }

    .kpi {
      display: grid;
      align-content: start;
      gap: var(--ov-space-2);
      padding: var(--ov-space-4) var(--ov-space-5);
      color: inherit;
      text-decoration: none;
    }

    a.kpi:hover {
      border-color: var(--ov-line-strong);
    }

    a.kpi:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .label {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .value {
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
      line-height: 1.1;
    }

    .unit {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-regular);
      letter-spacing: 0;
    }

    .muted-bar > span {
      background: var(--ov-ink-muted);
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-6);
    }

    .houses {
      display: grid;
      flex: 2 1 34rem;
      gap: var(--ov-space-3);
      min-width: 0;
    }

    .section-head,
    .panel-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .side {
      display: grid;
      flex: 1 1 19rem;
      gap: var(--ov-space-4);
      min-width: 0;
    }

    .panel {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-4) var(--ov-space-5);
    }

    .panel h2 {
      font-size: var(--ov-text-md);
    }

    .panel-head {
      padding-bottom: var(--ov-space-2);
    }

    .count {
      padding: 0.125rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-danger-soft);
      color: var(--ov-danger);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .review {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .icon {
      display: grid;
      flex: none;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-canvas);
      color: var(--ov-accent);
    }

    .review[data-kind='alert'] .icon {
      background: var(--ov-danger-soft);
      color: var(--ov-danger);
    }

    .icon mat-icon {
      width: 1.25rem;
      height: 1.25rem;
    }

    .review-text {
      flex: 1;
      min-width: 0;
    }

    .review-title {
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .empty {
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    /* En el teléfono los indicadores van de a dos: no empujan las casas fuera de la pantalla. */
    @media (max-width: 40rem) {
      .kpis {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: var(--ov-space-2);
      }

      .kpi {
        padding: var(--ov-space-3) var(--ov-space-4);
      }

      .value {
        font-size: var(--ov-text-lg);
      }
    }
  `,
})
export class WorkSummaryComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  private readonly developments = inject(DevelopmentsService);
  private readonly activity = inject(ActivityService);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly overview = signal<DevelopmentOverview | null>(null);
  protected readonly units = signal<Unit[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  private readonly audios = signal<PendingAudio[]>([]);
  private readonly alerts = signal<AlertStage[]>([]);
  protected readonly news = signal<FeedItem[]>([]);

  protected readonly workId = computed(() => this.store.development()?.id ?? '');
  protected readonly buckets = computed(() => houseBuckets(this.units()));
  protected readonly missingData = computed(() => missingDataPath(this.overview()?.setup ?? null));
  private readonly cards = computed(() => new Map((this.overview()?.houses ?? []).map(card => [card.unitId, card])));
  protected readonly review = computed(() => reviewItems(this.workId(), this.audios(), this.alerts()));
  protected readonly deliveryShort = computed(() => shortYearMonth(this.store.development()?.estimatedDelivery ?? null));
  protected readonly deliveryNote = computed(() => deliveryLine(this.store.development()?.estimatedDelivery ?? null));
  protected readonly money = compactMoney;

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const id = this.workId();
    if (!id) {
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    forkJoin({
      overview: this.developments.overview(id),
      units: this.developments.units(id),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ overview, units }) => {
          this.overview.set(overview);
          this.units.set(sortHouses(units));
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.errorMessage.set(errorMessageOf(error, 'No se pudo cargar el resumen del desarrollo.'));
        },
      });
    if (this.store.isOwner()) {
      this.loadSide(id);
    }
  }

  /** Lo del costado es apoyo: cada parte que falle queda vacía y el resumen se ve igual. */
  private loadSide(id: string): void {
    forkJoin({
      audios: this.activity.pendingAudios(id).pipe(catchError(() => of([] as PendingAudio[]))),
      panel: this.dashboard.dashboard(id).pipe(catchError(() => of(null))),
      feed: this.activity.feed(id, { limit: 5 }).pipe(catchError(() => of(null))),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ audios, panel, feed }) => {
      this.audios.set(audios);
      this.alerts.set(panel?.alerts ?? []);
      this.news.set(feed?.items ?? []);
    });
  }

  protected cardOf(unitId: string): HouseCard | null {
    return this.cards().get(unitId) ?? null;
  }

  protected round(value: number): number {
    return Math.round(value);
  }

  protected link(...path: string[]): string[] {
    return ['/obras', this.workId(), ...path];
  }
}

/**
 * La pestaña del desarrollo donde se completa el primer dato que falta; nula si no falta
 * ninguno o si quien mira no es el dueño (no recibe los pasos).
 */
export function missingDataPath(steps: SetupStep[] | null): string | null {
  const pending = firstMissingData(steps);
  return pending ? developmentStepPath(pending.key) : null;
}

/** Casa 2 antes que Casa 10. Vive en work-sections; se reexporta para quien ya lo importa de acá. */
export { sortHouses };
