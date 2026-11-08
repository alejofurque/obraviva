import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ReportDetail, StageStatus } from '../../core/api/api.models';
import { ReportsService } from '../../core/api/reports.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { StatusBadgeComponent } from '../../shared/ui/status-badge.component';
import { REPORT_STATUS_LABELS } from './work-labels';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

const STAGE_STATUSES: StageStatus[] = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'DELAYED'];

/**
 * Revisión de un reporte de período.
 *
 * <p>El texto y los datos van juntos a propósito: cada afirmación del reporte tiene que
 * poder contrastarse con el dato que la origina, sin navegar a otro lado. Se emite lo que
 * el revisor deja en el texto, no lo que escribió el modelo.
 */
@Component({
  selector: 'app-work-report-review',
  imports: [ShortDatePipe, ReactiveFormsModule, RouterLink, StatusBadgeComponent, LoadingStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page review">
      <a class="back ov-hit" [routerLink]="['/obras', developmentId, 'economico', 'reportes']">Reportes mensuales</a>

      @if (loading()) {
        <app-loading-state label="Cargando el reporte…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (report(); as current) {
        <header class="head">
          <div>
            <h2>{{ current.periodStart | shortDate: 'd MMM y' }} al {{ current.periodEnd | shortDate: 'd MMM y' }}</h2>
            <p class="muted">
              Generado el {{ current.createdAt | shortDate: 'd MMM y, HH:mm' }}
            </p>
          </div>
          <span class="ov-pill" [class.ov-pill--accent]="current.status === 'APPROVED'"
                [class.ov-pill--danger]="current.status === 'FAILED'">{{ labels[current.status] }}</span>
        </header>

        <div class="columns">
          <section class="ov-card block" aria-labelledby="text-title">
            <h3 id="text-title">Texto del informe</h3>
            <p class="muted">
              @if (editable()) {
                Corregí lo que haga falta. Se emite lo que quede acá.
              } @else {
                Este reporte ya fue resuelto y no admite cambios.
              }
            </p>
            <form [formGroup]="form" class="form">
              <label class="ov-visually-hidden" for="narrative">Informe</label>
              <textarea id="narrative" class="ov-input narrative" formControlName="narrative" rows="18"></textarea>
              @if (editable()) {
                <div class="ov-field">
                  <label class="ov-eyebrow" for="report-notes">Notas de revisión (opcional)</label>
                  <input id="report-notes" class="ov-input" formControlName="notes" maxlength="500" />
                </div>
              }
            </form>
            @if (current.reviewNotes) {
              <p class="muted">Notas: {{ current.reviewNotes }}</p>
            }
            @if (actionError(); as message) {
              <p class="ov-error" role="alert">{{ message }}</p>
            }
            @if (editable()) {
              <div class="actions">
                <button class="ov-btn ov-btn--primary" type="button" (click)="approve()" [disabled]="form.invalid || saving()">
                  {{ saving() ? 'Emitiendo…' : 'Aprobar y emitir' }}
                </button>
                <button class="ov-link" type="button" (click)="reject()" [disabled]="saving()">Rechazar</button>
              </div>
            }
          </section>

          <section class="ov-card block" aria-labelledby="data-title">
            <h3 id="data-title">Datos del período</h3>
            <p class="muted">Calculados por el sistema. El texto no puede afirmar nada que no esté acá.</p>

            <dl class="totals">
              <div><dt>Avances</dt><dd class="ov-num">{{ current.data.totals.progressEntries }}</dd></div>
              <div><dt>Recorridos 3D</dt><dd class="ov-num">{{ current.data.totals.captures }}</dd></div>
              <div><dt>Etapas completas</dt><dd class="ov-num">{{ current.data.totals.stagesCompleted }}</dd></div>
              <div><dt>En curso</dt><dd class="ov-num">{{ current.data.totals.stagesInProgress }}</dd></div>
              <div><dt>Demoradas</dt><dd class="ov-num">{{ current.data.totals.stagesDelayed }}</dd></div>
              <div><dt>Pendientes</dt><dd class="ov-num">{{ current.data.totals.stagesPending }}</dd></div>
            </dl>

            <h4>Avances</h4>
            @if (current.data.progressEntries.length === 0) {
              <p class="muted">Sin avances en el período.</p>
            }
            <ul class="plain">
              @for (entry of current.data.progressEntries; track $index) {
                <li class="item">
                  <p class="meta">
                    <span class="ov-num">{{ entry.date | shortDate: 'd MMM y' }}</span>
                    @if (entry.stageName) { · {{ entry.stageName }} }
                    · {{ entry.source === 'AUDIO' ? 'desde un audio' : entry.source === 'INITIAL_LOAD' ? 'carga inicial' : 'manual' }}
                  </p>
                  <p>{{ entry.description }}</p>
                </li>
              }
            </ul>

            <h4>Recorridos 3D</h4>
            @if (current.data.captures.length === 0) {
              <p class="muted">Sin recorridos en el período.</p>
            }
            <ul class="plain">
              @for (capture of current.data.captures; track capture.id) {
                <li class="item">
                  <p class="meta ov-num">{{ capture.date | shortDate: 'd MMM y' }}</p>
                  <p>{{ capture.description || 'Sin descripción' }}</p>
                </li>
              }
            </ul>

            <h4>Etapas</h4>
            <ul class="plain">
              @for (stage of current.data.stages; track stage.id) {
                <li class="stage">
                  <span>{{ stage.name }}</span>
                  @if (knownStatus(stage.statusAtCompositionTime); as status) {
                    <app-status-badge [status]="status" />
                  } @else {
                    <span class="muted">{{ stage.statusAtCompositionTime }}</span>
                  }
                </li>
              }
            </ul>
            @if (current.data.stages.length > 0) {
              <p class="muted">El estado de las etapas es el del momento en que se generó el reporte.</p>
            }
          </section>
        </div>
      }
    </div>
  `,
  styles: `
    .review {
      display: grid;
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    h3 {
      font-size: var(--ov-text-lg);
    }

    h4 {
      margin-top: var(--ov-space-4);
      font-size: var(--ov-text-md);
    }

    .muted,
    .meta {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .columns {
      display: grid;
      gap: var(--ov-space-5);
      align-items: start;
    }

    .block {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
    }

    .narrative {
      min-height: 24rem;
      line-height: var(--ov-leading-normal);
      resize: vertical;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .totals {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
    }

    .totals div {
      padding: var(--ov-space-3);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .totals dt {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .totals dd {
      margin: 0;
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
    }

    .plain {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .item {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-2) 0;
      border-top: 1px solid var(--ov-line);
    }

    .stage {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding: var(--ov-space-2) 0;
      border-top: 1px solid var(--ov-line);
    }

    @media (min-width: 64rem) {
      .columns {
        grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      }
    }
  `,
})
export class WorkReportReviewComponent implements OnInit, LeaveGuarded {

  private readonly store = inject(WorkStore);
  private readonly route = inject(ActivatedRoute);
  private readonly formBuilder = inject(FormBuilder);
  private readonly reportsService = inject(ReportsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = REPORT_STATUS_LABELS;
  protected readonly report = signal<ReportDetail | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = this.formBuilder.nonNullable.group({
    narrative: ['', Validators.required],
    notes: [''],
  });

  private readonly reportId = this.route.snapshot.paramMap.get('reportId') ?? '';

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.form.dirty ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    this.load();
  }

  protected editable(): boolean {
    return this.report()?.status === 'READY_FOR_REVIEW';
  }

  /** El snapshot guarda el estado como texto: sólo se pinta como insignia si es uno conocido. */
  protected knownStatus(status: string): StageStatus | null {
    return (STAGE_STATUSES as string[]).includes(status) ? status as StageStatus : null;
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.reportsService.detail(this.reportId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: report => {
        this.apply(report);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudo cargar el reporte.'));
      },
    });
  }

  protected approve(): void {
    const values = this.form.getRawValue();
    this.saving.set(true);
    this.actionError.set(null);
    this.reportsService.approve(this.reportId, { narrative: values.narrative, notes: values.notes.trim() || undefined }).subscribe({
      next: report => {
        this.saving.set(false);
        this.apply(report);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo aprobar el reporte.'));
      },
    });
  }

  protected reject(): void {
    if (!window.confirm('¿Rechazar el reporte? No se emite y no se puede deshacer.')) {
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    this.reportsService.reject(this.reportId, { notes: this.form.getRawValue().notes.trim() || undefined }).subscribe({
      next: report => {
        this.saving.set(false);
        this.apply(report);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo rechazar el reporte.'));
      },
    });
  }

  private apply(report: ReportDetail): void {
    this.report.set(report);
    this.form.patchValue({ narrative: report.narrative ?? '' });
    this.form.markAsPristine();
    // Un reporte resuelto se lee, no se edita.
    if (report.status === 'READY_FOR_REVIEW') {
      this.form.enable();
    } else {
      this.form.disable();
    }
  }
}
