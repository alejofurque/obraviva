import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, Subscription, forkJoin, last, map, of, startWith, switchMap, takeWhile, tap, timer } from 'rxjs';
import { AssignedHouse, ExtractionDetail, ExtractionSummary, Stage, SupplyAlert } from '../../core/api/api.models';
import { AudioExtractionsService } from '../../core/api/audio-extractions.service';
import { DevelopmentsService } from '../../core/api/developments.service';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, uploadCutWarning, warnOnUnload } from '../../shared/ui/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { ReviewCard, approvalEntries, audioContentType, audioStepLabel, elapsedLabel, reviewCards } from './audio-review';
import { entryDateLine } from './field-derivations';
import { FieldHeaderComponent } from './field-header.component';
import { FieldHouseStore } from './field-house.store';
import { FieldRecorderComponent } from './field-recorder.component';
import { FieldReviewCardComponent } from './field-review-card.component';
import { PendingPhotosState } from './pending-photos.component';

const POLL_INTERVAL_MS = 4000;
const MAX_POLLS = 150;
const MAX_AUDIO_BYTES = 100 * 1024 * 1024;

/** Si el navegador deja grabar: hace falta un contexto seguro (HTTPS o localhost) y MediaRecorder. */
export function canRecordAudio(scope: { isSecureContext?: boolean; MediaRecorder?: unknown; navigator?: Navigator } = globalThis): boolean {
  return !!scope.isSecureContext && typeof scope.MediaRecorder !== 'undefined'
    && typeof scope.navigator?.mediaDevices?.getUserMedia === 'function';
}

export { elapsedLabel } from './audio-review';

interface AudioUpload {
  file: Blob;
  name: string;
  contentType: string;
  percent: number;
  extractionId: string | null;
  uploadUrl: string | null;
  uploaded: boolean;
  failed: string | null;
}

/**
 * "Contarlo con audio": el encargado cuenta lo que pasó, la IA lo transcribe y lo
 * ordena, y él revisa y publica. Lo publicado llega al comprador igual que un avance
 * cargado a mano: pasa por la misma publicación, con fotos y cambio de etapa.
 *
 * <p>Dos momentos, dos rutas. Sin audio, se graba (si el navegador lo permite) o se elige
 * un archivo, incluido uno de WhatsApp, y se sube con su progreso. Con audio, se espera
 * mostrando el paso; se puede salir y volver desde "Hoy". Cuando está listo, la revisión
 * va en tarjetas: cada una es un avance. La IA propone estados, nunca porcentajes.
 */
@Component({
  selector: 'app-field-audio',
  imports: [RouterLink, LoadingStateComponent, ErrorStateComponent, FieldHeaderComponent, FieldRecorderComponent, FieldReviewCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      @if (loading()) {
        <app-loading-state label="Cargando…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (house(); as current) {
        <app-field-header [title]="published() ? 'Publicado' : 'Contarlo con audio'" [subtitle]="current.code + ' · ' + phase()" />

        @if (published()) {
          <section class="done" role="status">
            <p>{{ publishedCount() === 1 ? 'El avance ya está' : 'Los ' + publishedCount() + ' avances ya están' }} en la historia de {{ current.code }}.</p>
            <div class="done-actions">
              <!-- Publicado: los dos caminos reemplazan esta entrada, así el atrás no vuelve al formulario vacío. -->
              <a class="ov-btn ov-btn--primary ov-btn--block" [routerLink]="['/mi-propiedad', current.unitId]"
                 [queryParams]="{ obra: current.developmentId, desde: 'hoy' }" [replaceUrl]="true">Ver cómo lo ve el comprador</a>
              <a class="ov-btn ov-btn--secondary ov-btn--block" routerLink="/hoy" [replaceUrl]="true">Volver a Hoy</a>
            </div>
          </section>
        } @else if (!extractionId) {
          @if (upload(); as sending) {
            <section class="sending" aria-label="Subida del audio">
              <p>Subiendo el audio · <span class="ov-num">{{ sending.percent }}%</span></p>
              <div class="ov-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="sending.percent">
                <span [style.width.%]="sending.percent"></span>
              </div>
              @if (sending.failed; as message) {
                <p class="problem" role="alert">{{ message }}</p>
                <div class="row">
                  <button type="button" class="ov-btn ov-btn--primary" (click)="retryUpload()">Reintentar</button>
                  <button type="button" class="ov-link" (click)="cancelUpload()">Cancelar</button>
                </div>
              }
            </section>
          } @else if (chosen(); as audio) {
            <section class="chosen">
              <p><strong>{{ audio.label }}</strong></p>
              <button type="button" class="ov-btn ov-btn--primary ov-btn--block send" (click)="send()">Enviar</button>
              <button type="button" class="ov-link" (click)="chosen.set(null)">Elegir otro</button>
            </section>
          } @else {
            <section class="start">
              <p class="intro">Contá qué se hizo hoy, en qué etapa y si algo se demoró. La IA lo ordena y vos lo revisás antes de publicar.</p>
              @if (canRecord) {
                <app-field-recorder [recording]="recording()" [seconds]="elapsed()" [stream]="stream()" (toggle)="toggleRecording()" />
              }
              @if (!recording()) {
                <label class="ov-btn ov-btn--secondary ov-btn--block file">
                  Elegir un audio de WhatsApp
                  <input type="file" accept="audio/*,.ogg,.opus,.m4a" (change)="pick($event)" />
                </label>
                <p class="hint">
                  @if (canRecord) {
                    Guardá el audio de WhatsApp en el teléfono y elegilo acá; también sirve una nota de voz.
                  } @else {
                    Grabalo con la app de notas de voz o mandátelo por WhatsApp, y elegilo acá.
                  }
                </p>
              }
            </section>
          }
          @if (error(); as message) {
            <p class="problem" role="alert">{{ message }}</p>
          }
        } @else if (summary(); as state) {
          @if (state.reviewStatus === 'PENDING') {
            <ol class="steps" aria-label="Pasos">
              <li class="done-step">Audio subido{{ audioSeconds === null ? '' : ' · ' + audioClock }}</li>
              <li [class.current]="stepLabel() === 'Transcribiendo…'" [class.done-step]="stepLabel() === 'Ordenando…'">Transcribiendo…</li>
              <li [class.current]="stepLabel() === 'Ordenando…'">Ordenando las novedades</li>
            </ol>
            <p class="hint">Podés salir: queda en Hoy y lo retomás cuando esté listo.</p>
          } @else if (state.reviewStatus === 'FAILED') {
            <section class="notice" role="alert">
              <h2>No se pudo procesar el audio</h2>
              <p>{{ state.reviewNotes || 'Probá de nuevo o cargá el avance a mano.' }}</p>
              <a class="ov-btn ov-btn--primary ov-btn--block" [routerLink]="['/hoy', current.unitId, 'audio']">Enviar otro audio</a>
            </section>
          } @else if (state.reviewStatus === 'READY_FOR_REVIEW' && detail(); as extraction) {
            <details class="ov-disclosure transcript">
              <summary>Lo que dijiste</summary>
              <p>"{{ extraction.transcript }}"</p>
            </details>

            <div class="date">
              @if (editingDate()) {
                <label class="label" for="audio-date">Fecha</label>
                <input id="audio-date" class="ov-input" type="date" [max]="today"
                       [value]="date()" (change)="changeDate($any($event.target).value)" />
              } @else {
                <p class="date-line">
                  <span>Fecha: {{ dateLine() }}</span>
                  <button type="button" class="change" (click)="editingDate.set(true)">Cambiar</button>
                </p>
              }
            </div>

            <p class="label">La IA propone</p>
            @for (card of cards(); track card.key) {
              <app-field-review-card [card]="card" [position]="$index + 1" [stages]="stages()" [house]="current" [disabled]="busy()"
                                     (patch)="patchCard(card.key, $event)" (photos)="setPhotos(card.key, $event)" (remove)="removeCard(card.key)" />
            }

            @for (alert of alerts(); track $index) {
              <p class="alerts">
                <strong>Aviso de materiales:</strong> {{ alert.description }}. No se publica. Le llega al responsable en Novedades.
              </p>
            }

            <div class="publish-bar">
              @if (error(); as message) {
                <p class="problem" role="alert">{{ message }}</p>
              } @else if (blocker(); as reason) {
                <p class="hint">{{ reason }}</p>
              }
              <div class="bar-actions">
                <button type="button" class="ov-btn ov-btn--secondary discard" [disabled]="busy()" (click)="discard()">Descartar audio</button>
                <button type="button" class="ov-btn ov-btn--primary" [disabled]="busy() || !!blocker()" (click)="publish()">
                  {{ busy() ? 'Publicando…' : publishLabel() }}
                </button>
              </div>
            </div>
          } @else {
            <section class="notice">
              <h2>{{ stepLabel() }}</h2>
              <a class="ov-btn ov-btn--secondary ov-btn--block" routerLink="/hoy">Volver a Hoy</a>
            </section>
          }
        }
      }
    </div>
  `,
  styles: `
    /* Una columna que no crece con su contenido: un select con opciones largas no la ensancha. */
    .field {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--ov-space-5);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-3) var(--ov-gutter) 0;
    }

    .start,
    .chosen,
    .sending,
    .notice,
    .done,
    .done-actions,
    .date {
      display: grid;
      gap: var(--ov-space-3);
    }

    .start {
      gap: var(--ov-space-5);
      justify-items: center;
      text-align: center;
    }

    .intro {
      line-height: var(--ov-leading-normal);
    }

    .hint,
    .date-line {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .label {
      font-weight: var(--ov-weight-semibold);
    }

    .file,
    .send {
      position: relative;
      overflow: hidden;
      min-height: 3.5rem;
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

    .row,
    .date-line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .change {
      min-height: 3rem;
      border: 0;
      background: none;
      color: var(--ov-accent);
      font: inherit;
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .steps {
      display: grid;
      gap: var(--ov-space-4);
      margin: 0;
      padding: var(--ov-space-5);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      list-style: none;
    }

    .steps li {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      color: var(--ov-ink-muted);
    }

    .steps li::before {
      content: '';
      flex: none;
      width: 1.5rem;
      height: 1.5rem;
      border: 2px solid var(--ov-line-strong);
      border-radius: 50%;
    }

    .steps li.current {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .steps li.current::before {
      border-color: var(--ov-accent);
      border-right-color: transparent;
      animation: spin 900ms linear infinite;
    }

    .steps li.done-step {
      color: var(--ov-ink);
    }

    .steps li.done-step::before {
      border-color: var(--ov-accent);
      background: var(--ov-accent);
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    @media (prefers-reduced-motion: reduce) {
      .steps li.current::before {
        animation: none;
      }
    }

    .transcript {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .transcript summary {
      min-height: 3rem;
      align-content: center;
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .alerts {
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-status-delayed);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-status-delayed-soft);
      font-size: var(--ov-text-sm);
    }

    .problem::before {
      content: '⚠ ';
      color: var(--ov-status-delayed);
    }

    /* Publicar queda al alcance del pulgar mientras se revisan las tarjetas. */
    .publish-bar {
      position: sticky;
      bottom: 0;
      display: grid;
      gap: var(--ov-space-2);
      margin: 0 calc(-1 * var(--ov-gutter));
      padding: var(--ov-space-5) var(--ov-gutter) var(--ov-space-4);
      background: linear-gradient(0deg, var(--ov-canvas) 75%, transparent);
    }

    .bar-actions {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.6fr);
      gap: var(--ov-space-2);
    }

    .bar-actions .ov-btn {
      min-height: 3.5rem;
    }

    .done {
      padding: var(--ov-space-6) var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-accent-soft);
    }
  `,
})
export class FieldAudioComponent implements OnInit, LeaveGuarded {

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly developments = inject(DevelopmentsService);
  private readonly audioService = inject(AudioExtractionsService);
  private readonly stagesService = inject(StagesService);
  private readonly houseStore = inject(FieldHouseStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly today = todayLocal();
  protected readonly canRecord = canRecordAudio();
  private readonly unitId = this.route.snapshot.paramMap.get('unitId') ?? '';
  protected readonly extractionId = this.route.snapshot.paramMap.get('extractionId');

  protected readonly house = signal<AssignedHouse | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  // Grabar o elegir
  protected readonly recording = signal(false);
  protected readonly elapsed = signal(0);
  /** El micrófono mientras se graba, para la onda. */
  protected readonly stream = signal<MediaStream | null>(null);
  protected readonly chosen = signal<{ file: Blob; name: string; contentType: string; label: string; seconds: number | null } | null>(null);
  /** Cuánto dura el audio que se subió, si se grabó acá: llega a la espera con la navegación. */
  protected readonly audioSeconds: number | null = typeof globalThis.history?.state?.audioSeconds === 'number'
    ? globalThis.history.state.audioSeconds : null;
  protected readonly audioClock = elapsedLabel(this.audioSeconds ?? 0);
  protected readonly upload = signal<AudioUpload | null>(null);
  private recorder: MediaRecorder | null = null;
  private ticker: Subscription | null = null;
  private transfer: Subscription | null = null;

  // Espera y revisión
  protected readonly summary = signal<ExtractionSummary | null>(null);
  protected readonly detail = signal<ExtractionDetail | null>(null);
  protected readonly stages = signal<Stage[]>([]);
  protected readonly cards = signal<ReviewCard[]>([]);
  protected readonly alerts = signal<SupplyAlert[]>([]);
  protected readonly date = signal(todayLocal());
  protected readonly editingDate = signal(false);
  private readonly photos = signal<Record<number, PendingPhotosState>>({});
  protected readonly busy = signal(false);
  protected readonly published = signal(false);
  protected readonly publishedCount = signal(0);

  protected readonly clock = computed(() => elapsedLabel(this.elapsed()));
  protected readonly dateLine = computed(() => entryDateLine(this.date(), this.today));
  protected readonly publishLabel = computed(() => {
    const count = this.cards().length;
    return count === 0 ? 'Publicar' : count === 1 ? 'Publicar 1 avance' : `Publicar ${count} avances`;
  });
  /** En qué momento está, para la línea de abajo del título. */
  protected readonly phase = computed(() => {
    if (this.extractionId) {
      return this.summary()?.reviewStatus === 'READY_FOR_REVIEW' ? 'Revisar y publicar' : 'Procesando';
    }
    if (this.recording()) return 'Grabando';
    return this.upload() ? 'Subiendo' : 'Grabar';
  });
  protected readonly stepLabel = computed(() => {
    const state = this.summary();
    return state ? audioStepLabel(state) : '';
  });

  /** Por qué no se puede publicar todavía, en una línea; nulo si se puede. */
  protected readonly blocker = computed(() => {
    const cards = this.cards();
    const photos = Object.values(this.photos());
    if (cards.length === 0) return 'No queda nada para publicar: si el audio no servía, descartalo.';
    if (cards.some(card => card.description.trim() === '')) return 'Completá qué se hizo en cada avance.';
    if (this.date() > this.today || !this.date()) return 'La fecha no puede ser futura.';
    if (photos.some(state => state.busy)) return 'Esperá a que terminen de subir las fotos.';
    if (photos.some(state => state.failed)) return 'Reintentá o quitá las fotos que no subieron.';
    return null;
  });

  /** La revisión cambió algo (tarjetas, fecha, fotos): salir lo pierde. */
  private readonly reviewEdited = signal(false);
  /** Salir por un camino propio que ya se confirmó (descartar el audio). */
  private leaving = false;

  constructor() {
    warnOnUnload(() => this.leaveWarning());
    this.destroyRef.onDestroy(() => {
      this.ticker?.unsubscribe();
      if (this.recorder?.state === 'recording') {
        this.recorder.stop();
      }
    });
  }

  /**
   * Qué se pierde al salir: la grabación en curso, el audio elegido sin enviar, la subida
   * (que se corta) o lo corregido en la revisión. Lo que ya subió queda en Hoy y no se avisa.
   */
  leaveWarning(): string | null {
    if (this.leaving || this.published()) {
      return null;
    }
    if (this.recording()) {
      return 'Estás grabando. Si salís, la grabación se pierde. ¿Salir igual?';
    }
    const upload = this.upload();
    if (upload && !upload.uploaded && !upload.failed) {
      return uploadCutWarning('el audio');
    }
    if (this.chosen() && !upload) {
      return UNSAVED_CHANGES;
    }
    if (Object.values(this.photos()).some(state => state.busy)) {
      return uploadCutWarning('fotos');
    }
    return this.reviewEdited() ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.developments.myWork().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: houses => {
        const house = houses.find(candidate => candidate.unitId === this.unitId) ?? null;
        if (!house) {
          this.loading.set(false);
          this.loadError.set('No estás asignado a esta casa.');
          return;
        }
        this.house.set(house);
        this.loading.set(false);
        if (this.extractionId) {
          this.watch(house);
        }
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudo cargar la casa.'));
      },
    });
  }

  // --- Grabar o elegir ---

  protected toggleRecording(): void {
    if (this.recording()) {
      this.stopRecording();
    } else {
      void this.startRecording();
    }
  }

  protected async startRecording(): Promise<void> {
    this.error.set(null);
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      this.error.set('No se pudo usar el micrófono. Revisá el permiso del navegador, o grabá con otra app y elegí el archivo.');
      return;
    }
    const chunks: Blob[] = [];
    const recorder = new MediaRecorder(stream);
    recorder.ondataavailable = event => chunks.push(event.data);
    recorder.onstop = () => {
      stream.getTracks().forEach(track => track.stop());
      this.stream.set(null);
      const type = (recorder.mimeType || 'audio/webm').split(';')[0];
      const extension = type.includes('mp4') ? 'm4a' : type.includes('ogg') ? 'ogg' : 'webm';
      const blob = new Blob(chunks, { type });
      this.chosen.set({
        file: blob, name: `parte-${this.today}.${extension}`, contentType: type, label: `Audio grabado, ${this.clock()}`,
        seconds: this.elapsed(),
      });
      this.recording.set(false);
    };
    this.recorder = recorder;
    this.stream.set(stream);
    recorder.start();
    this.elapsed.set(0);
    this.recording.set(true);
    const started = Date.now();
    this.ticker = timer(0, 500).subscribe(() => this.elapsed.set((Date.now() - started) / 1000));
  }

  protected stopRecording(): void {
    this.ticker?.unsubscribe();
    this.recorder?.stop();
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) {
      return;
    }
    const contentType = audioContentType(file);
    if (!contentType.startsWith('audio/')) {
      this.error.set('El archivo tiene que ser un audio, como uno de WhatsApp o de notas de voz.');
      return;
    }
    if (file.size > MAX_AUDIO_BYTES) {
      this.error.set('El audio pesa más de 100 MB. Mandá uno más corto.');
      return;
    }
    this.error.set(null);
    this.chosen.set({ file, name: file.name, contentType, label: file.name, seconds: null });
  }

  protected send(): void {
    const audio = this.chosen();
    if (!audio) {
      return;
    }
    this.upload.set({
      file: audio.file, name: audio.name, contentType: audio.contentType,
      percent: 0, extractionId: null, uploadUrl: null, uploaded: false, failed: null,
    });
    this.runUpload();
  }

  protected retryUpload(): void {
    this.upload.update(current => current ? { ...current, failed: null } : current);
    this.runUpload();
  }

  protected cancelUpload(): void {
    this.transfer?.unsubscribe();
    this.upload.set(null);
  }

  /** Alta, subida con progreso y confirmación; un reintento sigue desde donde se cortó. */
  private runUpload(): void {
    const current = this.upload();
    const house = this.house();
    if (!current || !house) {
      return;
    }
    const created: Observable<{ extractionId: string; uploadUrl: string; contentType: string }> = current.extractionId
      ? of({ extractionId: current.extractionId, uploadUrl: current.uploadUrl!, contentType: current.contentType })
      : this.audioService.create(house.developmentId, {
        audioFilename: current.name, audioContentType: current.contentType, unitId: house.unitId,
      }).pipe(map(job => ({ extractionId: job.extractionId, uploadUrl: job.uploadUrl!, contentType: job.contentType || current.contentType })));

    this.transfer?.unsubscribe();
    this.transfer = created.pipe(
      tap(job => this.upload.update(state => state ? { ...state, extractionId: job.extractionId, uploadUrl: job.uploadUrl, contentType: job.contentType } : state)),
      switchMap(job => this.upload()?.uploaded
        ? of(job)
        : this.audioService.uploadAudioWithProgress(job.uploadUrl, current.file, job.contentType).pipe(
          tap(percent => this.upload.update(state => state ? { ...state, percent } : state)),
          last(),
          tap(() => this.upload.update(state => state ? { ...state, uploaded: true, percent: 100 } : state)),
          map(() => job))),
      switchMap(job => this.audioService.confirmUpload(job.extractionId).pipe(map(() => job.extractionId))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: extractionId => void this.router.navigate(['/hoy', this.unitId, 'audio', extractionId],
        { replaceUrl: true, state: { audioSeconds: this.chosen()?.seconds ?? null } }),
      error: (error: unknown) => {
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        if (status === 0 || status >= 500) {
          this.upload.update(state => state ? {
            ...state,
            failed: status === 0 ? 'Se cortó la conexión. Reintentá: no hace falta volver a grabar.' : errorMessageOf(error, 'El servidor no respondió.'),
          } : state);
          return;
        }
        this.upload.set(null);
        this.error.set(errorMessageOf(error, 'No se pudo enviar el audio.'));
      },
    });
  }

  // --- Espera ---

  /** Consulta el paso hasta que el audio está listo, falló o ya se resolvió. */
  private watch(house: AssignedHouse): void {
    const find = () => this.audioService.listByDevelopment(house.developmentId)
      .pipe(map(list => list.find(item => item.id === this.extractionId) ?? null));
    // La primera consulta sale en el acto; después, cada pocos segundos.
    timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS).pipe(
      startWith(-1),
      switchMap(() => find()),
      takeWhile((state, tick) => tick < MAX_POLLS && (state?.reviewStatus === 'PENDING'), true),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: state => {
        if (!state) {
          this.loadError.set('No se encontró el audio.');
          return;
        }
        this.summary.set(state);
        if (state.reviewStatus === 'READY_FOR_REVIEW' && !this.detail()) {
          this.loadReview();
        }
      },
      error: (error: unknown) => this.loadError.set(errorMessageOf(error, 'No se pudo consultar el audio.')),
    });
  }

  private loadReview(): void {
    forkJoin({
      extraction: this.audioService.detail(this.extractionId!),
      stages: this.stagesService.listByUnit(this.unitId),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ extraction, stages }) => {
        this.stages.set([...stages].sort((a, b) => a.orderIndex - b.orderIndex));
        this.cards.set(reviewCards(extraction.proposal));
        this.alerts.set(extraction.proposal?.supplyAlerts ?? []);
        this.detail.set(extraction);
      },
      error: (error: unknown) => this.loadError.set(errorMessageOf(error, 'No se pudo cargar la revisión.')),
    });
  }

  // --- Revisión ---

  /** Editar deja atrás el error del intento anterior: lo que importa ahora es lo que falta. */
  protected changeDate(value: string): void {
    this.date.set(value);
    this.reviewEdited.set(true);
  }

  protected patchCard(key: number, changes: Partial<ReviewCard>): void {
    this.error.set(null);
    this.reviewEdited.set(true);
    this.cards.update(cards => cards.map(card => card.key === key ? { ...card, ...changes } : card));
  }

  protected setPhotos(key: number, state: PendingPhotosState): void {
    if (state.ids.length > 0 || state.busy || state.failed) {
      this.reviewEdited.set(true);
    }
    this.photos.update(all => ({ ...all, [key]: state }));
  }

  protected removeCard(key: number): void {
    this.error.set(null);
    this.reviewEdited.set(true);
    this.cards.update(cards => cards.filter(card => card.key !== key));
    this.photos.update(all => {
      const rest = { ...all };
      delete rest[key];
      return rest;
    });
  }

  protected publish(): void {
    if (this.busy() || this.blocker()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const photoIds = Object.fromEntries(Object.entries(this.photos()).map(([key, state]) => [key, state.ids]));
    const photoSectors = Object.fromEntries(Object.entries(this.photos()).map(([key, state]) => [key, state.sectors]));
    this.audioService.approve(this.extractionId!, {
      entryDate: this.date(),
      entries: approvalEntries(this.cards(), photoIds, photoSectors),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: result => {
        this.busy.set(false);
        this.publishedCount.set(result.publishedEntries);
        this.published.set(true);
        this.houseStore.invalidate();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo publicar. No se publicó nada; lo que corregiste sigue acá.'));
      },
    });
  }

  protected discard(): void {
    if (!window.confirm('¿Descartar el audio? No se publica nada.')) {
      return;
    }
    this.busy.set(true);
    this.audioService.reject(this.extractionId!, {}).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.leaving = true;
        void this.router.navigate(['/hoy'], { replaceUrl: true });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo descartar el audio.'));
      },
    });
  }
}
