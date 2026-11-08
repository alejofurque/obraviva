import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { switchMap, take, takeWhile, timer } from 'rxjs';
import { ReportDetail, ReportSummary } from '../../core/api/api.models';
import { ReportsService } from '../../core/api/reports.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { REPORT_STATUS_LABELS } from './work-labels';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

const POLL_INTERVAL_MS = 4000;
const MAX_POLLS = 30;

/** Fecha con los componentes locales: toISOString pasa a UTC y en Argentina puede dar el día anterior. */
function isoDate(date: Date): string {
  return `${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, '0')}-${`${date.getDate()}`.padStart(2, '0')}`;
}

/** El mes anterior completo, que es el período natural de un reporte mensual. */
export function previousMonth(today = new Date()): { start: string; end: string } {
  return {
    start: isoDate(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
    end: isoDate(new Date(today.getFullYear(), today.getMonth(), 0)),
  };
}

/**
 * Reportes mensuales para inversores. El sistema junta los avances, recorridos y estados
 * del período; un modelo de lenguaje redacta el texto y el dueño lo revisa antes de
 * emitirlo. Ninguna cifra sale del modelo.
 *
 * <p>La espera se resuelve consultando: el backend corre en local y no puede avisar al
 * navegador. La consulta se corta sola al terminar la redacción y tiene tope.
 */
@Component({
  selector: 'app-work-reports',
  imports: [ShortDatePipe, ReactiveFormsModule, RouterLink, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow reports">
      <header class="intro">
        <a class="back ov-hit" [routerLink]="['/obras', developmentId, 'economico']">Económico</a>
        <h2>Reportes mensuales</h2>
        <p class="ov-lead">
          El informe del período para inversores. Los datos los calcula el sistema; el texto lo redacta la IA y
          vos lo revisás antes de emitirlo.
        </p>
      </header>

      <section class="ov-card panel" aria-labelledby="new-title">
        <h3 id="new-title">Nuevo reporte</h3>
        <form [formGroup]="form" (ngSubmit)="generate()" class="form" novalidate>
          <div class="ov-field">
            <label class="ov-eyebrow" for="period-start">Desde</label>
            <input id="period-start" class="ov-input" type="date" formControlName="periodStart" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="period-end">Hasta</label>
            <input id="period-end" class="ov-input" type="date" formControlName="periodEnd" />
          </div>
          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="form.invalid || generating() || waiting()">
            {{ generating() ? 'Reuniendo datos…' : 'Generar reporte' }}
          </button>
        </form>
        @if (waiting()) {
          <p class="phase" role="status">Redactando el reporte. Puede tardar hasta un minuto.</p>
        }
        @if (actionError(); as message) {
          <p class="ov-error" role="alert">{{ message }}</p>
        }
      </section>

      <section aria-labelledby="list-title">
        <h3 id="list-title">Reportes de la obra</h3>
        @if (loading()) {
          <app-loading-state label="Cargando reportes…" />
        } @else if (loadError(); as message) {
          <app-error-state [message]="message" (retry)="reload()" />
        } @else if (reports().length === 0) {
          <app-empty-state title="Todavía no hay reportes" message="Generá el primero con el período que quieras informar." />
        } @else {
          <ul class="list">
            @for (report of reports(); track report.id) {
              <li class="row" [class.attention]="report.status === 'READY_FOR_REVIEW'">
                <div>
                  <p class="period">
                    {{ report.periodStart | shortDate: 'd MMM y' }} al {{ report.periodEnd | shortDate: 'd MMM y' }}
                  </p>
                  <p class="muted ov-num">
                    {{ report.progressEntries }} {{ report.progressEntries === 1 ? 'avance' : 'avances' }} ·
                    {{ report.captures }} {{ report.captures === 1 ? 'recorrido' : 'recorridos' }}
                  </p>
                </div>
                <span class="ov-pill" [class.ov-pill--accent]="report.status === 'APPROVED'"
                      [class.ov-pill--danger]="report.status === 'FAILED'"
                      [class.ov-pill--outline]="report.status === 'REJECTED'">{{ labels[report.status] }}</span>
                @if (report.status !== 'PENDING') {
                  <a [class]="report.status === 'READY_FOR_REVIEW' ? 'ov-btn ov-btn--primary' : 'ov-btn ov-btn--secondary'"
                     [routerLink]="['/obras', developmentId, 'economico', 'reportes', report.id]">
                    {{ report.status === 'READY_FOR_REVIEW' ? 'Revisar' : 'Ver' }}
                  </a>
                }
              </li>
            }
          </ul>
        }
      </section>
    </div>
  `,
  styles: `
    .reports {
      display: grid;
      gap: var(--ov-space-6);
      --ov-measure: 52rem;
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: grid;
      gap: var(--ov-space-2);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    h3 {
      margin-bottom: var(--ov-space-3);
      font-size: var(--ov-text-lg);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
      align-items: end;
    }

    .phase {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .row {
      display: grid;
      grid-template-columns: 1fr auto;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) 0;
      border-top: 1px solid var(--ov-line);
    }

    .row.attention {
      padding-left: var(--ov-space-4);
      border-left: 3px solid var(--ov-status-delayed);
    }

    .period {
      font-weight: var(--ov-weight-medium);
    }

    @media (min-width: 48rem) {
      .form {
        grid-template-columns: 1fr 1fr auto;
      }

      .row {
        grid-template-columns: 1fr auto auto;
      }
    }
  `,
})
export class WorkReportsComponent implements OnInit {

  private readonly store = inject(WorkStore);
  private readonly reportsService = inject(ReportsService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = REPORT_STATUS_LABELS;
  protected readonly reports = signal<ReportSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly generating = signal(false);
  protected readonly waiting = signal(false);

  protected readonly form = this.formBuilder.nonNullable.group({
    periodStart: [previousMonth().start, Validators.required],
    periodEnd: [previousMonth().end, Validators.required],
  });

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    this.reload();
  }

  protected reload(): void {
    this.reportsService.listByDevelopment(this.developmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: reports => {
          this.reports.set(reports);
          this.loading.set(false);
          this.loadError.set(null);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los reportes.'));
        },
      });
  }

  protected generate(): void {
    this.generating.set(true);
    this.actionError.set(null);
    this.reportsService.generate(this.developmentId, this.form.getRawValue()).subscribe({
      next: report => {
        this.generating.set(false);
        this.reload();
        this.waitFor(report);
      },
      error: (error: unknown) => {
        this.generating.set(false);
        // El backend explica el motivo (período vacío, rango invertido), que sirve más que un genérico.
        this.actionError.set(errorMessageOf(error, 'No se pudo generar el reporte.'));
      },
    });
  }

  private waitFor(report: ReportDetail): void {
    if (report.status !== 'PENDING') {
      return;
    }
    this.waiting.set(true);
    timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
      .pipe(
        take(MAX_POLLS),
        switchMap(() => this.reportsService.detail(report.id)),
        takeWhile(current => current.status === 'PENDING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: current => {
          if (current.status === 'PENDING') {
            return;
          }
          this.waiting.set(false);
          if (current.status === 'FAILED') {
            this.actionError.set('La redacción falló. Los datos del período quedaron guardados.');
          }
          this.reload();
        },
        error: () => {
          this.waiting.set(false);
          this.actionError.set('Se perdió el seguimiento del reporte. Actualizá la página para ver su estado.');
        },
        complete: () => this.waiting.set(false),
      });
  }
}
