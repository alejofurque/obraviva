import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { AssignedHouse, Stage } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { todayLocal } from '../../shared/time/local-date';
import { ShortDatePipe } from '../../shared/time/short-date';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { StageCard, stageBoard } from '../works/stage-board';
import { StageCardComponent } from '../works/stage-card.component';

/**
 * "La casa" del encargado: la casa para mirar en la obra, desde el teléfono. Las etapas en
 * curso primero, con la misma tarjeta de Etapas del dueño, "Qué incluye" y "Publicar avance"
 * con esa etapa elegida; las demás, plegadas por estado. Lugar por lugar es del comprador y
 * no va acá.
 *
 * <p>Sólo lee las etapas de la casa, que llegan sin presupuesto para el encargado: nada de
 * precio, compradores, enlaces ni estado de venta.
 */
@Component({
  selector: 'app-field-house',
  imports: [RouterLink, ShortDatePipe, LoadingStateComponent, ErrorStateComponent, StageCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      @if (loading()) {
        <app-loading-state label="Cargando la casa…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (house(); as current) {
        <a class="back ov-hit" routerLink="/hoy">Hoy</a>

        <header class="head">
          <p class="place">{{ current.developmentName }}</p>
          <h1>{{ current.code }}</h1>
          <div class="bar">
            <div class="ov-progress" aria-hidden="true"><span [style.width.%]="current.progressPercent ?? 0"></span></div>
            <span class="ov-num">{{ current.progressPercent ?? 0 }}%</span>
          </div>
          <p class="muted">{{ board().done.length }} de {{ stages().length }} etapas terminadas</p>
        </header>

        @if (stages().length === 0) {
          <p class="muted">Esta casa todavía no tiene etapas.</p>
        } @else {
          <section class="group" aria-labelledby="active-title">
            <h2 id="active-title" class="group-title">En curso <span class="count">· {{ board().active.length }}</span></h2>
            @for (card of board().active; track card.stage.id) {
              <app-stage-card [card]="card">
                @if (card.stage.description) {
                  <details class="ov-disclosure includes">
                    <summary>Qué incluye</summary>
                    <p>{{ card.stage.description }}</p>
                  </details>
                }
                <a class="ov-btn ov-btn--secondary publish" [routerLink]="['/hoy', current.unitId, 'avance']"
                   [queryParams]="{ etapa: card.stage.id }" [attr.aria-label]="'Publicar avance de ' + card.stage.name">Publicar avance</a>
              </app-stage-card>
            } @empty {
              <p class="muted">Ninguna etapa está en curso.</p>
            }
          </section>

          @for (group of folded(); track group.title) {
            <details class="ov-disclosure ov-disclosure--wide ov-card folded">
              <summary><span>{{ group.title }} <span class="count">· {{ group.cards.length }}</span></span></summary>
              <ol class="rows">
                @for (card of group.cards; track card.stage.id) {
                  <li class="row">
                    <div class="row-head">
                      <span class="ov-num num">{{ number(card.stage) }}</span>
                      <span class="row-name">{{ card.stage.name }}</span>
                      @if (card.overdue) {
                        <span class="when late">Fin previsto {{ card.stage.plannedEnd | shortDate: 'd MMM' }} · pasó</span>
                      } @else if (card.stage.status === 'COMPLETED' && card.stage.completedOn) {
                        <span class="when">{{ card.stage.completedOn | shortDate: 'MMM yy' }}</span>
                      } @else if (card.stage.status === 'PENDING' && card.stage.plannedStart) {
                        <span class="when">Inicio previsto {{ card.stage.plannedStart | shortDate: 'd MMM' }}</span>
                      }
                    </div>
                    @if (card.stage.description) {
                      <details class="ov-disclosure includes">
                        <summary>Qué incluye</summary>
                        <p>{{ card.stage.description }}</p>
                      </details>
                    }
                  </li>
                }
              </ol>
            </details>
          }
        }
      }
    </div>
  `,
  styles: `
    .field {
      display: grid;
      gap: var(--ov-space-6);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-5) var(--ov-gutter) var(--ov-space-8);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .head,
    .group {
      display: grid;
      gap: var(--ov-space-2);
    }

    .group {
      gap: var(--ov-space-3);
    }

    .place,
    .muted,
    .count,
    .num,
    .when {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-regular);
    }

    h1 {
      font-size: var(--ov-text-xl);
      letter-spacing: -0.02em;
    }

    .bar {
      display: grid;
      grid-template-columns: 1fr auto;
      align-items: center;
      gap: var(--ov-space-3);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .group-title {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .includes {
      font-size: var(--ov-text-sm);
    }

    .includes > summary {
      color: var(--ov-ink-muted);
    }

    .includes p {
      line-height: 1.45;
    }

    .publish {
      width: 100%;
    }

    .folded > summary {
      padding: 0 var(--ov-space-5);
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .rows {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .row {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-3) var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
    }

    .row-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--ov-space-1) var(--ov-space-3);
    }

    .row-name {
      flex: 1 1 10rem;
      font-weight: var(--ov-weight-medium);
    }

    .num {
      font-size: var(--ov-text-xs);
    }

    .when.late {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-semibold);
    }
  `,
})
export class FieldHouseComponent implements OnInit {

  private readonly route = inject(ActivatedRoute);
  private readonly developments = inject(DevelopmentsService);
  private readonly stagesService = inject(StagesService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly unitId = this.route.snapshot.paramMap.get('unitId') ?? '';
  private readonly today = todayLocal();
  protected readonly house = signal<AssignedHouse | null>(null);
  protected readonly stages = signal<Stage[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** Las mismas cuentas que Etapas del dueño; sin avances, la tarjeta no lleva "Último avance". */
  protected readonly board = computed(() => stageBoard(this.stages(), [], this.today));

  /** Lo que no está en curso, plegado por estado. Un grupo vacío no aparece. */
  protected readonly folded = computed((): { title: string; cards: StageCard[] }[] => [
    { title: 'Por empezar', cards: this.board().upcoming },
    { title: 'Terminadas', cards: this.board().done },
  ].filter(group => group.cards.length > 0));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      houses: this.developments.myWork(),
      stages: this.stagesService.listByUnit(this.unitId),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ houses, stages }) => {
        const house = houses.find(candidate => candidate.unitId === this.unitId) ?? null;
        this.loading.set(false);
        if (!house) {
          this.loadError.set('No estás asignado a esta casa.');
          return;
        }
        this.house.set(house);
        this.stages.set(stages);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudo cargar la casa.'));
      },
    });
  }

  protected number(stage: Stage): string {
    return String(stage.orderIndex).padStart(2, '0');
  }
}
