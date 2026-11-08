import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map, switchMap } from 'rxjs';
import { ConfidenceLevel, ExtractionDetail, Stage, SupplyAlert } from '../../core/api/api.models';
import { AudioExtractionsService } from '../../core/api/audio-extractions.service';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { BackLinkDirective } from '../../core/routing/back-navigation';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { CONFIDENCE_LABELS, EXTRACTION_STATUS_LABELS } from './work-labels';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';
import { approvalEntries, reviewCards } from '../field/audio-review';
import { ShortDatePipe } from '../../shared/time/short-date';
import { STAGE_STATUS_OPTIONS } from '../../shared/ui/stage-status';
import { AudioPlayerComponent } from '../../shared/ui/audio-player.component';

type StageProposalForm = FormGroup<{
  stageId: FormControl<string>;
  proposedStatus: FormControl<string>;
  rationale: FormControl<string>;
  confidence: FormControl<ConfidenceLevel>;
}>;

type ProgressNoteForm = FormGroup<{
  stageId: FormControl<string>;
  description: FormControl<string>;
  confidence: FormControl<ConfidenceLevel>;
}>;

/** Un avance no devuelve una etapa a pendiente: el servidor lo rechaza. */
const STATUS_OPTIONS = STAGE_STATUS_OPTIONS.filter(option => option.value !== 'PENDING');

/**
 * Revisión de un parte por audio.
 *
 * <p>La transcripción va al lado de la propuesta porque son cosas distintas y el revisor
 * tiene que juzgar el salto entre ellas: la transcripción local es imperfecta y el modelo
 * corrige por contexto. Ver sólo la propuesta ocultaría esas correcciones.
 *
 * <p>Se publica lo que el revisor deja acá, y el servidor lo revalida contra las etapas
 * reales de la obra: lo que llega del navegador no es confiable.
 */
@Component({
  selector: 'app-work-audio-review',
  imports: [AudioPlayerComponent, ShortDatePipe, ReactiveFormsModule, RouterLink, BackLinkDirective, LoadingStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page review">
      <a class="back ov-hit" [appBack]="scope.link('historia', 'audios')">Volver</a>

      @if (loading()) {
        <app-loading-state label="Cargando el parte…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (extraction(); as current) {
        <header class="head">
          <div>
            <h2>Parte del {{ current.createdAt | shortDate: 'd MMM y, HH:mm' }}</h2>
            <p class="muted">
              @if (editable()) {
                Corregí lo que haga falta: se publica lo que quede acá, no lo que interpretó el modelo.
              } @else {
                Este parte ya fue resuelto y no admite cambios.
              }
            </p>
          </div>
          <span class="ov-pill" [class.ov-pill--accent]="current.reviewStatus === 'APPROVED'"
                [class.ov-pill--danger]="current.reviewStatus === 'FAILED'">{{ statusLabels[current.reviewStatus] }}</span>
        </header>

        @if (result(); as summary) {
          <div class="result" role="status">
            Se {{ summary.published === 1 ? 'publicó 1 avance' : 'publicaron ' + summary.published + ' avances' }}
            y se {{ summary.stages === 1 ? 'actualizó 1 etapa' : 'actualizaron ' + summary.stages + ' etapas' }}.
            <a [routerLink]="scope.link('historia')">Ver en la historia</a>
          </div>
        }

        <div class="columns">
          <section class="ov-card block" aria-labelledby="proposal-title">
            <h3 id="proposal-title">Qué propone publicar</h3>

            <form [formGroup]="form" class="date-row">
              <div class="ov-field">
                <label class="ov-eyebrow" for="entry-date">Fecha del avance</label>
                <input id="entry-date" class="ov-input" type="date" formControlName="entryDate" [max]="today" />
              </div>
            </form>

            <h4>Cambios de estado de etapas</h4>
            @if (stageProposals.controls.length === 0) {
              <p class="muted">El audio no propone cambios de estado.</p>
            }
            @for (group of stageProposals.controls; track $index) {
              <div class="item" [formGroup]="group">
                <div class="pair">
                  <div class="ov-field">
                    <label class="ov-eyebrow" [for]="'proposal-stage-' + $index">Etapa</label>
                    <select class="ov-input" [id]="'proposal-stage-' + $index" formControlName="stageId">
                      <option value="" disabled>Elegí la etapa</option>
                      @for (stage of stages(); track stage.id) {
                        <option [value]="stage.id">{{ stage.name }}</option>
                      }
                    </select>
                  </div>
                  <div class="ov-field">
                    <label class="ov-eyebrow" [for]="'proposal-status-' + $index">Estado</label>
                    <select class="ov-input" [id]="'proposal-status-' + $index" formControlName="proposedStatus">
                      @for (option of statusOptions; track option.value) {
                        <option [value]="option.value">{{ option.label }}</option>
                      }
                    </select>
                  </div>
                </div>
                @if (group.controls.rationale.value) {
                  <p class="rationale">«{{ group.controls.rationale.value }}»</p>
                }
                <p class="meta" [class.low]="group.controls.confidence.value === 'LOW'">
                  {{ confidenceLabels[group.controls.confidence.value] }}
                  @if (editable()) {
                    <button class="ov-link" type="button" (click)="stageProposals.removeAt($index); stageProposals.markAsDirty()">Descartar</button>
                  }
                </p>
              </div>
            }

            <h4>Novedades para la historia</h4>
            @if (progressNotes.controls.length === 0) {
              <p class="muted">El audio no aporta novedades.</p>
            }
            @for (group of progressNotes.controls; track $index) {
              <div class="item" [formGroup]="group">
                <div class="ov-field">
                  <label class="ov-eyebrow" [for]="'note-text-' + $index">Qué se hizo</label>
                  <textarea class="ov-input" [id]="'note-text-' + $index" formControlName="description" rows="3"></textarea>
                </div>
                <div class="ov-field">
                  <label class="ov-eyebrow" [for]="'note-stage-' + $index">Etapa</label>
                  <select class="ov-input" [id]="'note-stage-' + $index" formControlName="stageId">
                    <option value="">General de la obra</option>
                    @for (stage of stages(); track stage.id) {
                      <option [value]="stage.id">{{ stage.name }}</option>
                    }
                  </select>
                </div>
                <p class="meta" [class.low]="group.controls.confidence.value === 'LOW'">
                  {{ confidenceLabels[group.controls.confidence.value] }}
                  @if (editable()) {
                    <button class="ov-link" type="button" (click)="progressNotes.removeAt($index); progressNotes.markAsDirty()">Descartar</button>
                  }
                </p>
              </div>
            }

            @if (supplyAlerts().length > 0) {
              <h4>Faltantes mencionados</h4>
              <p class="muted">Son para tu información: no se publican en la historia.</p>
              <ul class="alerts">
                @for (alert of supplyAlerts(); track $index) {
                  <li>{{ alert.description }} <span class="meta">· {{ confidenceLabels[alert.confidence] }}</span></li>
                }
              </ul>
            }

            @if (editable()) {
              <form [formGroup]="form">
                <div class="ov-field">
                  <label class="ov-eyebrow" for="review-notes">Notas de revisión (opcional)</label>
                  <input id="review-notes" class="ov-input" formControlName="notes" maxlength="500" />
                </div>
              </form>
            }
            @if (current.reviewNotes) {
              <p class="muted">Notas: {{ current.reviewNotes }}</p>
            }
            @if (actionError(); as message) {
              <p class="ov-error" role="alert">{{ message }}</p>
            }

            @if (editable()) {
              <div class="actions">
                <button class="ov-btn ov-btn--primary" type="button" (click)="approve()"
                        [disabled]="saving() || (stageProposals.controls.length === 0 && progressNotes.controls.length === 0)">
                  {{ saving() ? 'Publicando…' : 'Confirmar y publicar' }}
                </button>
                <button class="ov-link" type="button" (click)="reject()" [disabled]="saving()">Descartar el parte</button>
              </div>
            }
          </section>

          <section class="ov-card block" aria-labelledby="transcript-title">
            <h3 id="transcript-title">Lo que dijo</h3>
            <p class="muted">Transcripción automática, con los errores que haya cometido.</p>
            @if (current.audioUrl) {
              <app-audio-player [src]="current.audioUrl" label="el parte" />
            }
            @if (current.transcript) {
              <p class="transcript">{{ current.transcript }}</p>
            } @else {
              <p class="muted">Todavía no hay transcripción.</p>
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

    .meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .meta.low {
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-medium);
    }

    .result {
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .result a {
      margin-left: var(--ov-space-2);
      color: inherit;
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

    .date-row {
      max-width: 14rem;
    }

    .item {
      display: grid;
      gap: var(--ov-space-3);
      padding-left: var(--ov-space-4);
      border-left: 3px solid var(--ov-line-strong);
    }

    .pair {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
      gap: var(--ov-space-3);
    }

    .rationale {
      color: var(--ov-ink-muted);
      font-style: italic;
    }

    .alerts {
      margin: 0;
      padding-left: var(--ov-space-5);
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-4);
      margin-top: var(--ov-space-3);
    }

    .transcript {
      max-width: var(--ov-reading-max);
      white-space: pre-wrap;
      line-height: var(--ov-leading-normal);
    }

    @media (min-width: 64rem) {
      .columns {
        grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      }
    }
  `,
})
export class WorkAudioReviewComponent implements OnInit, LeaveGuarded {

  private readonly store = inject(WorkStore);
  private readonly route = inject(ActivatedRoute);
  private readonly formBuilder = inject(FormBuilder);
  private readonly audioService = inject(AudioExtractionsService);
  private readonly stagesService = inject(StagesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly today = todayLocal();
  protected readonly statusOptions = STATUS_OPTIONS;
  protected readonly statusLabels = EXTRACTION_STATUS_LABELS;
  protected readonly confidenceLabels = CONFIDENCE_LABELS;

  protected readonly extraction = signal<ExtractionDetail | null>(null);
  protected readonly stages = signal<Stage[]>([]);
  protected readonly supplyAlerts = signal<SupplyAlert[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly result = signal<{ published: number; stages: number } | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    entryDate: [todayLocal(), Validators.required],
    notes: [''],
  });

  protected readonly stageProposals = new FormArray<StageProposalForm>([]);
  protected readonly progressNotes = new FormArray<ProgressNoteForm>([]);

  private readonly extractionId = this.route.snapshot.paramMap.get('extractionId') ?? '';

  protected readonly scope = injectWorkScope();

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.form.dirty || this.stageProposals.dirty || this.progressNotes.dirty ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    this.load();
  }

  protected editable(): boolean {
    return this.extraction()?.reviewStatus === 'READY_FOR_REVIEW';
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    // Las etapas que se proponen son las del lugar del parte: su casa, o las obras comunes.
    this.audioService.detail(this.extractionId)
      .pipe(
        switchMap(extraction => (extraction.unitId
          ? this.stagesService.listByUnit(extraction.unitId)
          : this.stagesService.listByDevelopment(this.developmentId))
          .pipe(map(stages => ({ extraction, stages })))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ extraction, stages }) => {
          this.stages.set(stages);
          this.apply(extraction);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudo cargar el parte.'));
        },
      });
  }

  protected approve(): void {
    if (this.form.invalid || this.stageProposals.invalid || this.progressNotes.invalid) {
      this.actionError.set('Completá la etapa de cada cambio de estado y el texto de cada novedad antes de publicar.');
      return;
    }
    // Cada novedad es un avance, con el cambio de estado de su etapa si lo hay; un cambio
    // de estado sin novedad publica su fundamento como texto. Las alertas no viajan.
    const cards = reviewCards({
      stageProposals: this.stageProposals.controls.map(group => {
        const raw = group.getRawValue();
        return { stageId: raw.stageId || null, proposedStatus: raw.proposedStatus, rationale: raw.rationale || null, confidence: raw.confidence };
      }),
      progressNotes: this.progressNotes.controls.map(group => {
        const raw = group.getRawValue();
        return { stageId: raw.stageId || null, description: raw.description.trim(), confidence: raw.confidence };
      }),
      supplyAlerts: [],
    });
    if (cards.some(card => card.description.trim() === '')) {
      this.actionError.set('Un cambio de estado sin novedad publica su fundamento como texto: completalo antes de publicar.');
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    const values = this.form.getRawValue();
    this.audioService.approve(this.extractionId, {
      entryDate: values.entryDate,
      notes: values.notes.trim() || undefined,
      entries: approvalEntries(cards),
    }).subscribe({
      next: approval => {
        this.saving.set(false);
        this.result.set({ published: approval.publishedEntries, stages: approval.updatedStages });
        this.load();
        // Publicar puede mover estados y avance: los encabezados de la obra y de la casa se ponen al día.
        this.scope.refresh();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo publicar el parte.'));
      },
    });
  }

  protected reject(): void {
    if (!window.confirm('¿Descartar el parte? No se publica nada y no se puede deshacer.')) {
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    this.audioService.reject(this.extractionId, { notes: this.form.getRawValue().notes.trim() || undefined }).subscribe({
      next: extraction => {
        this.saving.set(false);
        this.apply(extraction);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo descartar el parte.'));
      },
    });
  }

  private apply(extraction: ExtractionDetail): void {
    this.extraction.set(extraction);
    this.supplyAlerts.set(extraction.proposal?.supplyAlerts ?? []);
    this.stageProposals.clear();
    this.progressNotes.clear();
    // Lo que viene del servidor es el punto de partida: nada sin guardar todavía.
    this.form.markAsPristine();
    this.stageProposals.markAsPristine();
    this.progressNotes.markAsPristine();

    for (const proposal of extraction.proposal?.stageProposals ?? []) {
      this.stageProposals.push(this.formBuilder.nonNullable.group({
        // Un cambio de estado sin etapa no significa nada: el servidor lo descartaría.
        stageId: [proposal.stageId ?? '', Validators.required],
        proposedStatus: [proposal.proposedStatus ?? 'IN_PROGRESS', Validators.required],
        rationale: [proposal.rationale ?? ''],
        confidence: [proposal.confidence],
      }));
    }
    for (const note of extraction.proposal?.progressNotes ?? []) {
      this.progressNotes.push(this.formBuilder.nonNullable.group({
        stageId: [note.stageId ?? ''],
        description: [note.description, Validators.required],
        confidence: [note.confidence],
      }));
    }

    if (extraction.reviewStatus === 'READY_FOR_REVIEW') {
      this.form.enable();
      this.stageProposals.enable();
      this.progressNotes.enable();
    } else {
      this.form.disable();
      this.stageProposals.disable();
      this.progressNotes.disable();
    }
  }
}
