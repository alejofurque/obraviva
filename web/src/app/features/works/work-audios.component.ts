import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { switchMap, take, takeWhile, timer } from 'rxjs';
import { AudioExtractionJob, ExtractionSummary } from '../../core/api/api.models';
import { AudioExtractionsService } from '../../core/api/audio-extractions.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { EXTRACTION_STATUS_LABELS } from './work-labels';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

/** Etapas visibles del alta, para que se sepa dónde está la espera. */
export type UploadPhase = 'idle' | 'registering' | 'uploading' | 'confirming' | 'processing';

const PHASE_LABELS: Record<Exclude<UploadPhase, 'idle'>, string> = {
  registering: 'Registrando el parte…',
  uploading: 'Subiendo el audio…',
  confirming: 'Confirmando la subida…',
  processing: 'Transcribiendo e interpretando. Puede tardar un par de minutos: podés seguir usando la obra.',
};

const POLL_INTERVAL_MS = 5000;
/**
 * Tope de consultas. La transcripción corre a unas siete veces el tiempo real y la
 * interpretación suma la latencia del proveedor: cinco minutos de margen alcanzan y
 * evitan preguntar para siempre si el worker murió.
 */
const MAX_POLLS = 60;
const MAX_AUDIO_BYTES = 50 * 1024 * 1024;

/**
 * Partes por audio de la obra: el encargado cuenta el avance hablando, como ya lo hace
 * por mensaje, y el sistema lo transcribe y propone una actualización que una persona
 * revisa antes de publicar.
 *
 * <p>El alta tiene tres pasos contra el backend (registrar, subir con URL firmada,
 * confirmar) y una espera larga. Por eso el estado se muestra por etapas: un minuto de
 * silencio sin explicación se interpreta como que algo se colgó.
 */
@Component({
  selector: 'app-work-audios',
  imports: [ShortDatePipe, RouterLink, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow audios">
      <header class="intro">
        <a class="back ov-hit" [routerLink]="scope.link('historia')">Historia</a>
        <h2>Partes por audio</h2>
        <p class="ov-lead">
          Contá el avance hablando. El sistema lo transcribe y propone qué publicar; una persona lo revisa,
          corrige lo que haga falta y confirma. Nada se publica sin esa confirmación.
        </p>
      </header>

      <section class="ov-card panel" aria-labelledby="upload-title">
        <h3 id="upload-title">Nuevo parte</h3>
        <div class="upload">
          <label class="ov-btn ov-btn--secondary file">
            {{ file()?.name ?? 'Elegir audio' }}
            <input type="file" accept="audio/*,.ogg,.opus,.m4a" (change)="pick($event)" [disabled]="busy()" />
          </label>
          <button class="ov-btn ov-btn--primary" type="button" [disabled]="!file() || busy()" (click)="upload()">
            Subir y procesar
          </button>
        </div>
        <p class="muted">Sirve el audio de WhatsApp tal como llega, sin convertirlo.</p>
        @if (phaseLabel(); as label) {
          <p class="phase" role="status">{{ label }}</p>
        }
        @if (actionError(); as message) {
          <p class="ov-error" role="alert">{{ message }}</p>
        }
      </section>

      <section aria-labelledby="list-title">
        <h3 id="list-title">Partes de la obra</h3>
        @if (loading()) {
          <app-loading-state label="Cargando los partes…" />
        } @else if (loadError(); as message) {
          <app-error-state [message]="message" (retry)="reload()" />
        } @else if (extractions().length === 0) {
          <app-empty-state title="Todavía no hay partes por audio"
            message="Cuando subas uno, aparece acá con su estado hasta que se publique." />
        } @else {
          <ul class="list">
            @for (extraction of extractions(); track extraction.id) {
              <li class="row" [class.attention]="extraction.reviewStatus === 'READY_FOR_REVIEW'">
                <div class="row-main">
                  <p class="meta">
                    <span class="ov-num">{{ extraction.createdAt | shortDate: 'd MMM y, HH:mm' }}</span>
                    @if (extraction.proposedElements > 0) {
                      · {{ extraction.proposedElements }} {{ extraction.proposedElements === 1 ? 'propuesta' : 'propuestas' }}
                    }
                  </p>
                  @if (extraction.transcriptPreview) {
                    <p class="preview">«{{ extraction.transcriptPreview }}»</p>
                  }
                </div>
                <span class="ov-pill" [class.ov-pill--accent]="extraction.reviewStatus === 'APPROVED'"
                      [class.ov-pill--danger]="extraction.reviewStatus === 'FAILED'"
                      [class.ov-pill--outline]="extraction.reviewStatus === 'REJECTED'">
                  {{ labels[extraction.reviewStatus] }}
                </span>
                @if (extraction.reviewStatus !== 'PENDING') {
                  <a [class]="extraction.reviewStatus === 'READY_FOR_REVIEW' ? 'ov-btn ov-btn--primary' : 'ov-btn ov-btn--secondary'"
                     [routerLink]="scope.link('historia', 'audios', extraction.id)">
                    {{ extraction.reviewStatus === 'READY_FOR_REVIEW' ? 'Revisar' : 'Ver' }}
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
    .audios {
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

    .upload {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-3);
    }

    .file {
      position: relative;
      overflow: hidden;
      max-width: 100%;
    }

    .file input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .file:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
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

    .row-main {
      grid-column: 1 / -1;
      display: grid;
      gap: var(--ov-space-1);
    }

    .meta {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .preview {
      display: -webkit-box;
      overflow: hidden;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }

    @media (min-width: 48rem) {
      .row {
        grid-template-columns: 1fr auto auto;
      }

      .row-main {
        grid-column: auto;
      }
    }
  `,
})
export class WorkAudiosComponent implements OnInit {

  private readonly store = inject(WorkStore);
  private readonly audioService = inject(AudioExtractionsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = EXTRACTION_STATUS_LABELS;
  protected readonly extractions = signal<ExtractionSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly phase = signal<UploadPhase>('idle');

  protected readonly busy = computed(() => this.phase() !== 'idle' && this.phase() !== 'processing');
  protected readonly phaseLabel = computed(() => {
    const phase = this.phase();
    return phase === 'idle' ? null : PHASE_LABELS[phase];
  });

  protected readonly scope = injectWorkScope();

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    this.reload();
  }

  protected reload(): void {
    this.audioService.listByDevelopment(this.developmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: extractions => {
          // Los partes de este lugar: la casa abierta, o las obras comunes.
          this.extractions.set(extractions.filter(extraction => this.scope.owns(extraction.unitId)));
          this.loading.set(false);
          this.loadError.set(null);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los partes.'));
        },
      });
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const picked = input.files?.[0] ?? null;
    input.value = '';
    this.actionError.set(null);
    if (picked && picked.size > MAX_AUDIO_BYTES) {
      this.actionError.set('El audio pesa más de 50 MB. Un parte de obra dura unos minutos: probá con uno más corto.');
      this.file.set(null);
      return;
    }
    this.file.set(picked);
  }

  protected upload(): void {
    const file = this.file();
    if (!file) {
      return;
    }
    this.actionError.set(null);
    this.phase.set('registering');
    // El navegador no siempre reconoce el tipo de un .ogg de mensajería, y el backend
    // exige el dato: ante la duda se declara el genérico y decide ffmpeg, que lee el contenido.
    const contentType = file.type || 'application/octet-stream';

    this.audioService.create(this.developmentId, { audioFilename: file.name, audioContentType: contentType, unitId: this.scope.unitId() }).subscribe({
      next: job => this.send(job, file, contentType),
      error: (error: unknown) => this.fail(error, 'No se pudo registrar el parte.'),
    });
  }

  private send(job: AudioExtractionJob, file: File, contentType: string): void {
    if (!job.uploadUrl) {
      this.fail(null, 'El servidor no devolvió dónde subir el audio.');
      return;
    }
    this.phase.set('uploading');
    this.audioService.uploadAudio(job.uploadUrl, file, job.contentType || contentType).subscribe({
      next: () => this.confirm(job),
      // Un fallo acá suele ser del almacenamiento, no del backend.
      error: (error: unknown) => this.fail(error, 'No se pudo subir. Probá de nuevo en un rato.'),
    });
  }

  private confirm(job: AudioExtractionJob): void {
    this.phase.set('confirming');
    this.audioService.confirmUpload(job.extractionId).subscribe({
      next: () => {
        this.file.set(null);
        this.reload();
        this.waitFor(job.extractionId);
      },
      error: (error: unknown) => this.fail(error, 'No se pudo confirmar la subida.'),
    });
  }

  /** Consulta el parte hasta que deja de estar pendiente. */
  private waitFor(extractionId: string): void {
    this.phase.set('processing');
    timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
      .pipe(
        take(MAX_POLLS),
        switchMap(() => this.audioService.detail(extractionId)),
        takeWhile(current => current.reviewStatus === 'PENDING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: current => {
          if (current.reviewStatus === 'PENDING') {
            return;
          }
          this.phase.set('idle');
          if (current.reviewStatus === 'FAILED') {
            this.actionError.set('El procesamiento del audio falló. Podés volver a subirlo o publicar el avance a mano.');
          }
          this.reload();
        },
        error: () => {
          this.phase.set('idle');
          this.actionError.set('Se perdió el seguimiento del parte. Actualizá la página para ver su estado.');
        },
        complete: () => this.phase.set('idle'),
      });
  }

  private fail(error: unknown, fallback: string): void {
    this.phase.set('idle');
    this.actionError.set(error ? errorMessageOf(error, fallback) : fallback);
  }
}
