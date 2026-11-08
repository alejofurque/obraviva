import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import {
  ActivityItem, AlertStage, PublicPlace, ShareLinkSummary, Stage, UnitMember,
} from '../../core/api/api.models';
import { ActivityService } from '../../core/api/activity.service';
import { DashboardService } from '../../core/api/dashboard.service';
import { MyPropertyService } from '../../core/api/my-property.service';
import { StagesService } from '../../core/api/stages.service';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { ActivityLineComponent } from './activity-line.component';
import { HouseStore } from './house.store';
import { buyerLines, compactMoney, deliveryLine, placeTiles, shortYearMonth, stageMeta, stageSummary } from './overview-derivations';
import { firstMissingData, houseStepPath } from './work-labels';
import { WorkStore } from './work.store';
import { hasDelay, isPastPlannedEnd } from '../../shared/ui/stage-status';

/** Las etapas por su número, que es el orden de la obra. */
export function byOrder(stages: Stage[]): Stage[] {
  return [...stages].sort((a, b) => a.orderIndex - b.orderIndex);
}

/**
 * Las etapas que se están trabajando: en ejecución o demoradas, que también se están
 * haciendo, sólo que atrasadas. Varias a la vez es lo normal en una casa.
 */
export function runningStages(stages: Stage[]): Stage[] {
  return byOrder(stages).filter(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED');
}

/**
 * Resumen de una casa: cómo está hoy. Arriba los indicadores (etapas, gasto, entrega y, para
 * el dueño, el comprador); a la izquierda las etapas en curso, compactas, y los lugares con
 * su miniatura; a la derecha lo que pasó en la casa, con el formato corto de las novedades.
 * Las acciones viven en sus pestañas; la vista del comprador, en el encabezado de la casa.
 */
@Component({
  selector: 'app-house-summary',
  imports: [RouterLink, MatIconModule, VariantPipe, ActivityLineComponent, LoadingStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page summary">
      @if (house.overview(); as data) {
        @if (missingDataPath(); as path) {
          <p class="missing muted">
            Faltan algunos datos por completar · <a class="ov-link" [routerLink]="link(path)">Ver</a>
          </p>
        }

        <section class="kpis" aria-label="Cómo está la casa hoy">
          <div class="ov-card kpi">
            <p class="label">Etapas</p>
            @if (loading()) {
              <p class="value ov-num">…</p>
            } @else if (summary().total === 0) {
              <p class="value">Sin etapas</p>
            } @else {
              <p class="value ov-num">{{ summary().done }} <span class="unit">de {{ summary().total }} terminadas</span></p>
              <p class="sub">{{ summary().running }} en curso · {{ summary().delayed }} con demora</p>
            }
          </div>
          @if (data.economic; as economic) {
            <a class="ov-card kpi" [routerLink]="link('economico')">
              <p class="label">Gasto</p>
              <p class="value ov-num">
                {{ money(economic.totalSpent) }}@if (economic.totalBudget > 0) {{{ ' ' }}<span class="unit">de {{ money(economic.totalBudget) }}</span>}
              </p>
              @if (economic.budgetUsedPercent !== null) {
                <p class="sub ov-num" [class.alert]="alerts().length > 0" [class.ok]="alerts().length === 0">
                  {{ alerts().length > 0 ? alertLabel() : 'En línea' }} · {{ round(economic.budgetUsedPercent) }}% gastado, {{ house.unit()?.progressPercent ?? 0 }}% de avance
                </p>
              } @else {
                <p class="sub">Sin presupuesto cargado</p>
              }
            </a>
          }
          <div class="ov-card kpi">
            <p class="label">Entrega estimada</p>
            <p class="value">{{ deliveryShort() ?? 'Sin definir' }}</p>
            <p class="sub">{{ summary().next ? 'Próxima etapa: ' + summary().next : deliveryNote() }}</p>
          </div>
          @if (work.isOwner()) {
            <a class="ov-card kpi" [routerLink]="link('venta')">
              <p class="label">Comprador</p>
              <p class="value buyer">{{ buyer().name }}</p>
              <p class="sub">{{ buyer().detail }}</p>
            </a>
          }
        </section>

        <div class="columns">
          <div class="main">
            <section class="ov-card panel" aria-labelledby="running-title">
              <div class="panel-head">
                <h2 id="running-title">Etapas en curso</h2>
                <a class="ov-link" [routerLink]="link('etapas')">Todas las etapas</a>
              </div>
              @if (loading()) {
                <app-loading-state label="Cargando etapas…" />
              } @else if (loadError(); as message) {
                <app-error-state [message]="message" (retry)="load()" />
              } @else {
                @for (stage of running(); track stage.id) {
                  <div class="running">
                    <div class="running-name">
                      <p class="name">{{ stage.name }}</p>
                      <p class="meta" [class.late]="late(stage)">
                        {{ meta(stage) }}@if (overdue(stage) && stage.status !== 'DELAYED') { · con demora }
                      </p>
                    </div>
                    <div class="ov-progress" aria-hidden="true"><span [style.width.%]="stage.progressPercent"></span></div>
                    <p class="ov-num percent">{{ stage.progressPercent }}%</p>
                  </div>
                } @empty {
                  <p class="sub empty">{{ stages().length === 0 ? 'Esta casa todavía no tiene etapas.' : 'Ninguna etapa en ejecución.' }}</p>
                }
              }
            </section>

            <section class="ov-card panel" aria-labelledby="places-title">
              <div class="panel-head">
                <h2 id="places-title">Lugares</h2>
                <a class="ov-link" [routerLink]="link('recorridos')">Ver lugares y 3D</a>
              </div>
              @if (tiles().length === 0) {
                <p class="sub empty">Todavía no hay fotos ni recorridos por lugar.</p>
              } @else {
                <ul class="places">
                  @for (tile of tiles(); track tile.name) {
                    <li>
                      <a class="place" [routerLink]="link('recorridos')">
                        <span class="thumb" [class.tour-only]="!tile.thumb">
                          @if (tile.thumb) {
                            <img [src]="tile.thumb | variant: 'thumb'" alt="" loading="lazy" />
                          } @else {
                            <mat-icon svgIcon="view_in_ar" aria-hidden="true" />
                          }
                          @if (tile.has3d) {
                            <span class="badge">3D</span>
                          }
                        </span>
                        <span class="place-name">{{ tile.name }}</span>
                        <span class="place-meta">{{ tile.meta }}</span>
                      </a>
                    </li>
                  }
                </ul>
              }
            </section>
          </div>

          <section class="ov-card panel activity" aria-labelledby="activity-title">
            <div class="panel-head">
              <h2 id="activity-title">Actividad</h2>
              <a class="ov-link" [routerLink]="link('novedades')">Ver todo</a>
            </div>
            @for (item of moments(); track item.id) {
              <app-activity-line [item]="item" [showUnit]="false" />
            } @empty {
              <p class="sub empty">Todavía no hay avances, fotos ni recorridos de esta casa.</p>
            }
          </section>
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
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: var(--ov-space-3);
    }

    .kpi {
      display: grid;
      align-content: start;
      gap: var(--ov-space-1);
      padding: var(--ov-space-4) var(--ov-space-5);
      color: inherit;
      text-decoration: none;
    }

    a.kpi:hover {
      border-color: var(--ov-line-strong);
    }

    a.kpi:focus-visible,
    .place:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .label {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .value {
      margin-top: var(--ov-space-1);
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
      line-height: 1.15;
    }

    .value.buyer {
      font-size: var(--ov-text-lg);
    }

    .unit {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-regular);
      letter-spacing: 0;
    }

    .sub.ok {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .sub.alert {
      color: var(--ov-danger);
      font-weight: var(--ov-weight-medium);
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-6);
    }

    .main {
      display: grid;
      flex: 2 1 34rem;
      gap: var(--ov-space-6);
      min-width: 0;
    }

    .activity {
      flex: 1 1 19rem;
      min-width: 0;
    }

    .panel {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-5);
    }

    .panel-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding-bottom: var(--ov-space-2);
    }

    .panel h2 {
      font-size: var(--ov-text-md);
    }

    .running {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(6rem, 10rem) 3rem;
      align-items: center;
      gap: var(--ov-space-4);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .name {
      font-weight: var(--ov-weight-semibold);
    }

    .meta {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    /* La demora va en ámbar, como en Etapas: no es un error. */
    .meta.late {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-medium);
    }

    .percent {
      font-size: var(--ov-text-sm);
      text-align: right;
    }

    .empty {
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .places {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(8.75rem, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .place {
      display: grid;
      gap: var(--ov-space-1);
      color: inherit;
      text-decoration: none;
    }

    .thumb {
      position: relative;
      display: block;
      overflow: hidden;
      aspect-ratio: 4 / 3;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
    }

    /* Sin fotos del lugar: una tarjeta clara con el ícono del 3D (no se generan imágenes del 3D). */
    .thumb.tour-only {
      display: grid;
      place-items: center;
      border: 1px solid var(--ov-line);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .badge {
      position: absolute;
      top: var(--ov-space-2);
      left: var(--ov-space-2);
      padding: 0.0625rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: color-mix(in srgb, var(--ov-ink) 70%, transparent);
      color: var(--ov-on-ink);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .place-name {
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .place-meta {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    @media (max-width: 40rem) {
      .running {
        grid-template-columns: minmax(0, 1fr) 3rem;
      }

      .running .ov-progress {
        grid-column: 1 / -1;
        grid-row: 2;
      }
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

      .places {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
  `,
})
export class HouseSummaryComponent implements OnInit {

  protected readonly work = inject(WorkStore);
  protected readonly house = inject(HouseStore);
  private readonly stagesService = inject(StagesService);
  private readonly activity = inject(ActivityService);
  private readonly myProperty = inject(MyPropertyService);
  private readonly units = inject(UnitsService);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly stages = signal<Stage[]>([]);
  /** Lo que pasó en la casa: las mismas novedades del desarrollo, filtradas a esta casa. */
  protected readonly moments = signal<ActivityItem[]>([]);
  private readonly places = signal<PublicPlace[]>([]);
  private readonly members = signal<UnitMember[]>([]);
  private readonly links = signal<ShareLinkSummary[]>([]);
  protected readonly alerts = signal<AlertStage[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  private readonly today = todayLocal();

  /** La pestaña de la casa donde se completa el primer dato que falta. Sólo el dueño recibe los pasos. */
  protected readonly missingDataPath = computed(() => {
    const pending = firstMissingData(this.house.overview()?.setup ?? null);
    return pending ? houseStepPath(pending.key) : null;
  });

  protected readonly summary = computed(() => stageSummary(this.stages(), this.today));
  protected readonly running = computed(() => runningStages(this.stages()));
  protected readonly tiles = computed(() => placeTiles(this.places()));
  protected readonly buyer = computed(() => buyerLines(this.members(), this.links(), this.today));
  protected readonly alertLabel = computed(() => {
    const count = this.alerts().length;
    return count === 1 ? '1 etapa en alerta' : `${count} etapas en alerta`;
  });
  protected readonly deliveryShort = computed(() => shortYearMonth(this.house.unit()?.estimatedDelivery ?? null));
  protected readonly deliveryNote = computed(() => deliveryLine(this.house.unit()?.estimatedDelivery ?? null, this.today));
  protected readonly money = compactMoney;
  protected readonly meta = stageMeta;

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const unitId = this.house.unitId();
    const developmentId = this.work.development()?.id ?? '';
    this.loading.set(true);
    this.loadError.set(null);
    this.stagesService.listByUnit(unitId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: stages => {
          this.stages.set(stages);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar las etapas de la casa.'));
        },
      });
    // Lo demás es apoyo: cada parte que falle queda vacía y el Resumen se muestra igual.
    forkJoin({
      feed: this.activity.feed(developmentId, { unitId, limit: 6 }).pipe(catchError(() => of(null))),
      view: this.myProperty.property(unitId).pipe(catchError(() => of(null))),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ feed, view }) => {
      this.moments.set(feed?.items ?? []);
      this.places.set(view?.property.places ?? []);
    });
    if (this.work.isOwner()) {
      forkJoin({
        members: this.units.members(unitId).pipe(catchError(() => of([] as UnitMember[]))),
        links: this.myProperty.listShareLinks(unitId).pipe(catchError(() => of([] as ShareLinkSummary[]))),
        panel: this.dashboard.dashboard(developmentId, unitId).pipe(catchError(() => of(null))),
      }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ members, links, panel }) => {
        this.members.set(members);
        this.links.set(links);
        this.alerts.set(panel?.alerts ?? []);
      });
    }
  }

  protected link(...path: string[]): string[] {
    // Un paso que se resuelve en el propio Resumen trae la ruta vacía: se queda en la casa.
    return ['/obras', this.work.development()?.id ?? '', 'casas', this.house.unitId(), ...path.filter(Boolean)];
  }

  protected late(stage: Stage): boolean {
    return hasDelay(stage, this.today);
  }

  protected overdue(stage: Stage): boolean {
    return isPastPlannedEnd(stage, this.today);
  }

  protected round(value: number): number {
    return Math.round(value);
  }
}
