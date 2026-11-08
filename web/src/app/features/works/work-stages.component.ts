import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ProgressEntry, Stage } from '../../core/api/api.models';
import { ProgressService } from '../../core/api/progress.service';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { boardCounts, stageBoard } from './stage-board';
import { StageBoardComponent } from './stage-board.component';
import { StageEditComponent } from './stage-edit.component';
import { StageTableComponent } from './stage-table.component';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';

/** Avance ponderado, igual que el servidor: suma de avance por peso sobre la suma de pesos. */
export function weightedProgress(stages: Pick<Stage, 'progressPercent' | 'weight'>[]): number {
  const totalWeight = stages.reduce((sum, stage) => sum + stage.weight, 0);
  if (totalWeight === 0) {
    return 0;
  }
  const weighted = stages.reduce((sum, stage) => sum + stage.progressPercent * stage.weight, 0);
  return Math.round(weighted / totalWeight);
}

/**
 * Etapas de una casa, o de las obras comunes del desarrollo:
 * "Por estado" (en curso con su último avance, lo
 * que viene con "Empezar" y lo terminado) y "Lista" (la tabla completa).
 *
 * <p>"Actualizar avance" no cambia el número a secas: abre "Publicar avance" en la Historia
 * con la etapa elegida, y el texto y el cambio de la etapa salen juntos. "Empezar" pasa la
 * etapa a en curso con la fecha de hoy. "Editar" corrige sin publicar y, para el dueño, el
 * detalle. Dueño y encargado declaran avance y estado; estructurar es del dueño.
 */
@Component({
  selector: 'app-work-stages',
  imports: [
    ReactiveFormsModule, StageBoardComponent, StageTableComponent, StageEditComponent,
    LoadingStateComponent, ErrorStateComponent, EmptyStateComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page stages-page">
      <header class="intro">
        <div class="title">
          <h2>{{ scope.isHouse() ? 'Etapas de la casa' : 'Etapas de las obras comunes' }}</h2>
          @if (stages().length > 0) {
            <p class="summary">
              {{ counts() }}<span class="muted"> · Avance ponderado </span><span class="ov-num">{{ overall() }}%</span>
            </p>
          }
        </div>
        @if (stages().length > 0) {
          <div class="tools">
            <div class="segmented" role="radiogroup" aria-label="Vista">
              <button type="button" role="radio" class="ov-hit" [class.active]="!listView()" [attr.aria-checked]="!listView()"
                      (click)="setView(false)">Por estado</button>
              <button type="button" role="radio" class="ov-hit" [class.active]="listView()" [attr.aria-checked]="listView()"
                      (click)="setView(true)">Lista</button>
            </div>
            @if (store.isOwner()) {
              <button class="ov-btn ov-btn--primary" type="button" [attr.aria-expanded]="adding()"
                      aria-controls="new-stage-form" (click)="adding.set(!adding())">Agregar etapa</button>
            }
          </div>
        }
      </header>

      @if (store.isOwner() && !loading() && (adding() || (stages().length === 0 && !scope.isHouse()))) {
        <form id="new-stage-form" [formGroup]="form" (ngSubmit)="create()" class="ov-card create" novalidate>
          <div class="ov-field">
            <label class="ov-eyebrow" for="new-stage">Nueva etapa</label>
            <input id="new-stage" class="ov-input" formControlName="name"
                   [placeholder]="scope.isHouse() ? 'Por ejemplo: Parquización' : 'Por ejemplo: Portal de acceso'" />
          </div>
          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="form.invalid || creating()">
            {{ creating() ? 'Agregando…' : 'Agregar al final' }}
          </button>
          <p class="muted hint">
            El peso define cuánto cuenta cada etapa en el avance: una estructura con peso 15 cuenta cinco veces más que
            trabajos preliminares con peso 3. Se ajusta en "Editar".
          </p>
        </form>
      }

      @if (actionError(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }

      @if (loading()) {
        <app-loading-state label="Cargando etapas…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (stages().length === 0) {
        @if (scope.isHouse() && store.isOwner()) {
          <section class="ov-card template">
            <div>
              <h3>Cargar las etapas estándar de una casa</h3>
              <p class="muted">
                Doce etapas en el orden en que se construye, con una descripción y su peso en el avance:
                preliminares, fundaciones, estructura, mampostería, techo, instalaciones, revoques, aberturas,
                pisos, pintura y entrega. Después se ajustan.
              </p>
            </div>
            <button class="ov-btn ov-btn--primary" type="button" [disabled]="creating()" (click)="applyTemplate()">
              {{ creating() ? 'Cargando…' : 'Cargar etapas estándar' }}
            </button>
          </section>
        } @else if (!store.isOwner() || scope.isHouse()) {
          <app-empty-state
            title="Todavía no hay etapas"
            [message]="store.isOwner() ? 'Creá las etapas en el orden en que se construyen.' : 'El responsable todavía no cargó las etapas.'" />
        }
      } @else if (listView()) {
        <app-stage-table [stages]="ordered()" [today]="today" (edit)="editing.set($event)" />
      } @else {
        <app-stage-board [board]="board()" [historyLink]="scope.link('historia')" [busyId]="starting()"
                         (edit)="editing.set($event)" (start)="start($event)" />
      }

      @if (editing(); as stage) {
        <app-stage-edit [stage]="stage" [owner]="store.isOwner()" (saved)="replace($event)" (closed)="editing.set(null)" />
      }
    </div>
  `,
  styles: `
    .stages-page {
      display: grid;
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .title {
      display: grid;
      gap: var(--ov-space-1);
    }

    .summary {
      font-size: var(--ov-text-sm);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .tools {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2);
    }

    .segmented {
      display: flex;
      gap: var(--ov-space-1);
      padding: var(--ov-space-1);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .segmented button {
      min-height: 2.5rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .segmented button.active {
      background: var(--ov-surface);
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .create,
    .template {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .create .ov-field {
      flex: 1 1 18rem;
    }

    .hint {
      flex-basis: 100%;
    }

    .template > div {
      flex: 1 1 24rem;
    }
  `,
})
export class WorkStagesComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  protected readonly scope = injectWorkScope();
  private readonly stagesService = inject(StagesService);
  private readonly progress = inject(ProgressService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly today = todayLocal();
  protected readonly stages = signal<Stage[]>([]);
  private readonly entries = signal<ProgressEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly adding = signal(false);
  protected readonly starting = signal<string | null>(null);
  protected readonly editing = signal<Stage | null>(null);
  protected readonly listView = signal(this.route.snapshot.queryParamMap.get('vista') === 'lista');

  protected readonly ordered = computed(() => [...this.stages()].sort((a, b) => a.orderIndex - b.orderIndex));
  protected readonly board = computed(() => stageBoard(this.stages(), this.entries(), this.today));
  protected readonly counts = computed(() => boardCounts(this.board()));
  protected readonly overall = computed(() => weightedProgress(this.stages()));

  protected readonly form = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      stages: this.scope.stages(),
      // El último avance de cada etapa es un agregado: si no carga, las etapas se ven igual.
      entries: this.progress.list(this.scope.developmentId()).pipe(catchError(() => of<ProgressEntry[]>([]))),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ stages, entries }) => {
        this.stages.set(stages);
        this.entries.set(entries.filter(entry => this.scope.owns(entry.unitId)));
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudieron cargar las etapas.'));
      },
    });
  }

  /** La vista queda en la dirección: volver de la Historia la respeta. */
  protected setView(list: boolean): void {
    this.listView.set(list);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { vista: list ? 'lista' : null }, replaceUrl: true });
  }

  protected replace(updated: Stage): void {
    this.stages.update(stages => stages.map(item => (item.id === updated.id ? updated : item)));
    this.editing.update(current => (current?.id === updated.id ? updated : current));
    // Los encabezados muestran el avance de la casa y del desarrollo: se refrescan.
    this.scope.refresh();
  }

  protected start(stage: Stage): void {
    this.starting.set(stage.id);
    this.actionError.set(null);
    this.stagesService.updateStatus(stage.id, 'IN_PROGRESS').subscribe({
      next: updated => {
        this.starting.set(null);
        this.replace(updated);
      },
      error: (error: unknown) => {
        this.starting.set(null);
        this.actionError.set(errorMessageOf(error, 'No se pudo empezar la etapa.'));
      },
    });
  }

  protected applyTemplate(): void {
    const unitId = this.scope.unitId();
    if (!unitId) {
      return;
    }
    this.creating.set(true);
    this.actionError.set(null);
    this.stagesService.applyStandard(unitId).subscribe({
      next: stages => {
        this.creating.set(false);
        this.stages.set(stages);
        this.scope.refresh();
      },
      error: (error: unknown) => {
        this.creating.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudieron cargar las etapas estándar.'));
      },
    });
  }

  protected create(): void {
    const name = this.form.getRawValue().name.trim();
    if (!name) {
      return;
    }
    const nextOrder = this.stages().reduce((max, stage) => Math.max(max, stage.orderIndex), 0) + 1;
    const unitId = this.scope.unitId();
    const request = unitId
      ? this.stagesService.createForUnit(unitId, name, nextOrder)
      : this.stagesService.create(this.scope.developmentId(), name, nextOrder);
    this.creating.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: stage => {
        this.creating.set(false);
        this.form.reset();
        this.adding.set(false);
        this.stages.update(stages => [...stages, stage]);
        this.scope.refresh();
      },
      error: (error: unknown) => {
        this.creating.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo crear la etapa.'));
      },
    });
  }
}
