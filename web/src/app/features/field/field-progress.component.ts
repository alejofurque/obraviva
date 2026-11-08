import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
import { AssignedHouse, ProgressEntry, Stage, StageUpdatePayload } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { AuthService } from '../../core/auth/auth.service';
import { ProgressService } from '../../core/api/progress.service';
import { StagesService } from '../../core/api/stages.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, uploadCutWarning, warnOnUnload } from '../../shared/ui/leave-guard';
import { MAX_PHOTOS_PER_ENTRY } from '../../shared/media/photo-limit';
import { lastSector, rememberSector } from './sector-memory';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { selectPhotos } from '../works/work-progress.component';
import { entryDateLine, stageChips, workingStage } from './field-derivations';
import { FieldHeaderComponent } from './field-header.component';
import { FieldHouseStore } from './field-house.store';
import { FieldPhotoGridComponent } from './field-photo-grid.component';
import { FieldStageUpdateComponent } from './field-stage-update.component';

export { UPDATE_STATUSES, clampPercent, proposedStatus } from '../../shared/ui/stage-update.component';

/** Una foto elegida: sube apenas se elige, y el avance no sale hasta que todas terminaron. */
interface PendingPhoto {
  key: number;
  file: File;
  preview: string;
  state: 'uploading' | 'done' | 'failed';
  percent: number;
  mediaId: string | null;
  error: string | null;
  /** Lugar de la casa que muestra; nulo: "General". */
  sector: string | null;
}

/**
 * Publicar un avance desde la obra, pensado para el teléfono y en una sola columna:
 * la casa en el encabezado, la etapa como chips (las que
 * se trabajan, "General de la casa" y "Otra etapa…"), qué se hizo, las fotos con su lugar, el
 * avance de la etapa y la fecha. La casa es la de la dirección: se elige en Hoy.
 *
 * Las fotos suben apenas se eligen y quedan pendientes. Publicar manda el texto, los ids de
 * las fotos y el cambio de la etapa en un solo envío: el comprador ve todo junto o no ve
 * nada. Si el envío falla, el formulario queda como estaba y se reintenta con las mismas
 * fotos, que siguen pendientes.
 */
@Component({
  selector: 'app-field-progress',
  imports: [RouterLink, LoadingStateComponent, ErrorStateComponent, FieldHeaderComponent, FieldPhotoGridComponent, FieldStageUpdateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      @if (loading()) {
        <app-loading-state label="Cargando la casa…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (house(); as current) {
        <app-field-header [title]="published() ? (correcting ? 'Corregido' : 'Publicado') : (correcting ? 'Corregir avance' : 'Publicar avance')"
                          [subtitle]="current.code + ' · ' + current.developmentName" />

        @if (published()) {
          <section class="done" role="status">
            @if (correcting) {
              <p>La corrección ya está en la historia de {{ current.code }}.</p>
            } @else {
              <p>Ya está en la historia de {{ current.code }}{{ publishedStage() ? ', con ' + publishedStage() : '' }}.</p>
            }
            <div class="done-actions">
              <!-- Publicado: los dos caminos reemplazan esta entrada, así el atrás no vuelve al formulario vacío. -->
              <a class="ov-btn ov-btn--primary ov-btn--block" [routerLink]="['/mi-propiedad', current.unitId]"
                 [queryParams]="{ obra: current.developmentId, desde: 'hoy' }" [replaceUrl]="true">Ver cómo lo ve el comprador</a>
              <a class="ov-btn ov-btn--secondary ov-btn--block" routerLink="/hoy" [replaceUrl]="true">Volver a Hoy</a>
            </div>
          </section>
        } @else {
          <form class="form" (submit)="$event.preventDefault(); publish()" novalidate>
            <fieldset class="stages" [disabled]="busy()">
              <legend class="label">¿En qué etapa?</legend>
              <div class="chips">
                @for (option of chips(); track option.id) {
                  <button type="button" class="chip" [attr.aria-pressed]="option.id === stageId()" (click)="chooseStage(option.id)">
                    {{ option.name }} <span class="pct">{{ option.progressPercent }}%</span>
                  </button>
                }
                <button type="button" class="chip" [attr.aria-pressed]="stageId() === ''" (click)="chooseStage('')">General de la casa</button>
                @if (otherStages().length > 0) {
                  <button type="button" class="chip other" [attr.aria-expanded]="choosingOther()"
                          (click)="choosingOther.set(!choosingOther())">Otra etapa…</button>
                }
              </div>
              @if (choosingOther()) {
                <select id="field-stage-other" class="ov-input" aria-label="Otra etapa"
                        (change)="chooseOther($any($event.target).value)">
                  <option value="" selected disabled>Elegí la etapa</option>
                  @for (option of otherStages(); track option.id) {
                    <option [value]="option.id">{{ option.orderIndex }}. {{ option.name }}{{ option.status === 'COMPLETED' ? ' (terminada)' : '' }}</option>
                  }
                </select>
              }
            </fieldset>

            <div class="ov-field">
              <label class="label" for="field-text">¿Qué se hizo?</label>
              <textarea id="field-text" class="ov-input text" maxlength="2000" rows="4" required [disabled]="busy()"
                        placeholder="Por ejemplo: terminamos el revoque grueso del living y llegó el porcelanato."
                        [value]="text()" (input)="text.set($any($event.target).value)"></textarea>
            </div>

            <!-- Corregir cambia la etapa, el texto y la fecha; las fotos se agregan desde la historia. -->
            @if (!correcting) {
            <div class="ov-field">
              <app-field-photo-grid [photos]="photos()" [sectors]="current.sectors" [max]="maxPhotos" [disabled]="busy()"
                                    (picked)="pickPhotos($event)" (retry)="retryPhoto($event)" (remove)="removePhoto($event)"
                                    (sector)="chooseSector($event.key, $event.sector)" />
              @for (problem of problems(); track problem) {
                <p class="problem">{{ problem }}</p>
              }
            </div>

            }

            @if (!correcting && stage(); as chosen) {
              <app-field-stage-update idPrefix="field" [stage]="chosen" [disabled]="busy()" [(update)]="stageUpdate" />
            }

            <div class="date">
              @if (editingDate()) {
                <label class="label" for="field-date">Fecha</label>
                <input id="field-date" class="ov-input" type="date" [max]="today"
                       [value]="date()" [disabled]="busy()" (change)="date.set($any($event.target).value)" />
                @if (dateProblem(); as problem) {
                  <p class="problem">{{ problem }}</p>
                }
              } @else {
                <p class="date-line">
                  <span>Fecha: {{ dateLine() }}</span>
                  <button type="button" class="change" (click)="editingDate.set(true)">Cambiar</button>
                </p>
              }
            </div>

            <div class="publish-bar">
              @if (error(); as message) {
                <div class="failure" role="alert">
                  <p class="problem">{{ message }}</p>
                  <button type="button" class="ov-btn ov-btn--secondary" (click)="publish()" [disabled]="busy()">Reintentar</button>
                </div>
              }
              <button class="ov-btn ov-btn--primary ov-btn--block submit" type="submit" [disabled]="busy() || !!blocker()">
                {{ busy() ? (correcting ? 'Guardando…' : 'Publicando…') : (correcting ? 'Guardar corrección' : 'Publicar avance') }}
              </button>
              @if (!error() && blocker(); as reason) {
                <p class="reason">{{ reason }}</p>
              }
            </div>
          </form>
        }
      }
    </div>
  `,
  styles: `
    .field {
      display: grid;
      gap: var(--ov-space-5);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-3) var(--ov-gutter) 0;
    }

    .reason {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .form {
      display: grid;
      gap: var(--ov-space-6);
    }

    .label {
      font-weight: var(--ov-weight-semibold);
    }

    .stages {
      display: grid;
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      border: 0;
    }

    .stages legend {
      margin-bottom: var(--ov-space-3);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .chip {
      min-height: 3rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      cursor: pointer;
    }

    .chip[aria-pressed='true'] {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-surface);
    }

    .chip .pct {
      margin-left: var(--ov-space-1);
      font-weight: var(--ov-weight-regular);
      opacity: 0.8;
    }

    .chip.other {
      border-style: dashed;
    }

    .text {
      font-size: var(--ov-text-md);
    }

    .problem {
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
    }

    .problem::before {
      content: '⚠ ';
      color: var(--ov-status-delayed);
    }

    .date {
      display: grid;
      gap: var(--ov-space-2);
    }

    .date-line {
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .change {
      min-height: 3rem;
      padding: 0 var(--ov-space-2);
      border: 0;
      background: none;
      color: var(--ov-accent);
      font: inherit;
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    /* El botón queda al alcance del pulgar: fijo abajo mientras se completa el formulario. */
    .publish-bar {
      position: sticky;
      bottom: 0;
      display: grid;
      gap: var(--ov-space-2);
      margin: 0 calc(-1 * var(--ov-gutter));
      padding: var(--ov-space-5) var(--ov-gutter) var(--ov-space-4);
      background: linear-gradient(0deg, var(--ov-canvas) 75%, transparent);
      text-align: center;
    }

    .submit {
      min-height: 3.5rem;
      font-size: var(--ov-text-md);
    }

    .failure {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-2);
      text-align: left;
    }

    .done {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-6) var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-accent-soft);
    }

    .done-actions {
      display: grid;
      gap: var(--ov-space-3);
      margin-top: var(--ov-space-3);
    }
  `,
})
export class FieldProgressComponent implements OnInit, LeaveGuarded {

  private readonly route = inject(ActivatedRoute);
  private readonly developments = inject(DevelopmentsService);
  private readonly stagesService = inject(StagesService);
  private readonly progress = inject(ProgressService);
  private readonly auth = inject(AuthService);
  private readonly houseStore = inject(FieldHouseStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly today = todayLocal();
  protected readonly maxPhotos = MAX_PHOTOS_PER_ENTRY;

  protected readonly houses = signal<AssignedHouse[]>([]);
  protected readonly unitId = signal(this.route.snapshot.paramMap.get('unitId') ?? '');
  /** La etapa desde la que se llegó ("Ver la casa" → Publicar avance), si es de esta casa. */
  private readonly requestedStageId = this.route.snapshot.queryParamMap.get('etapa');
  private readonly router = inject(Router);
  /**
   * El avance a corregir (`/hoy/:unitId/avance/:entryId`, desde "Corregir"): la misma pantalla
   * con lo que tenía, sin fotos ni cambio de la etapa, que se manejan aparte.
   */
  private readonly entryId = this.route.snapshot.paramMap.get('entryId');
  protected readonly correcting = this.entryId !== null;
  /** Lo que tenía el avance, para saber si la corrección cambió algo. */
  private readonly original = signal<{ text: string; stageId: string; date: string } | null>(null);
  protected readonly stages = signal<Stage[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly stageId = signal('');
  protected readonly text = signal('');
  protected readonly photos = signal<PendingPhoto[]>([]);
  protected readonly problems = signal<string[]>([]);
  protected readonly date = signal(todayLocal());
  protected readonly editingDate = signal(false);
  protected readonly dateLine = computed(() => entryDateLine(this.date(), this.today));
  protected readonly choosingOther = signal(false);
  protected readonly chips = computed(() => stageChips(this.stages(), this.stageId()));
  /** Las que no están como chip: pendientes y terminadas, para "Otra etapa…". */
  protected readonly otherStages = computed(() => {
    const chips = this.chips();
    return this.stages().filter(stage => !chips.includes(stage));
  });
  /** El cambio de la etapa que viaja con el avance; nulo si no se actualiza. */
  protected readonly stageUpdate = signal<StageUpdatePayload | null>(null);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly published = signal(false);
  protected readonly publishedStage = signal<string | null>(null);

  private nextKey = 0;
  private readonly uploads = new Map<number, Subscription>();

  protected readonly house = computed(() => this.houses().find(house => house.unitId === this.unitId()) ?? null);
  protected readonly stage = computed(() => this.stages().find(stage => stage.id === this.stageId()) ?? null);
  protected readonly dateProblem = computed(() => {
    const date = this.date();
    if (!date) return 'Elegí la fecha del avance.';
    return date > this.today ? 'La fecha no puede ser futura.' : null;
  });

  /** Por qué no se puede publicar todavía, en una línea; nulo si se puede. */
  protected readonly blocker = computed(() => {
    const photos = this.photos();
    if (this.text().trim().length === 0) return 'Contá qué se hizo para poder publicar.';
    if (this.dateProblem()) return this.dateProblem();
    const uploading = photos.filter(photo => photo.state === 'uploading').length;
    if (uploading > 0) return `Esperando que termine de subir ${uploading === 1 ? '1 foto' : uploading + ' fotos'}.`;
    if (photos.some(photo => photo.state === 'failed')) return 'Reintentá o quitá las fotos que no subieron.';
    return null;
  });

  constructor() {
    warnOnUnload(() => this.leaveWarning());
    this.destroyRef.onDestroy(() => {
      this.uploads.forEach(upload => upload.unsubscribe());
      this.photos().forEach(photo => URL.revokeObjectURL(photo.preview));
    });
  }

  ngOnInit(): void {
    this.load();
  }

  /** Lo escrito y las fotos se pierden al salir; una vez publicado, ya no hay nada que perder. */
  leaveWarning(): string | null {
    if (this.published()) {
      return null;
    }
    const original = this.original();
    if (original) {
      const changed = this.text().trim() !== original.text || this.stageId() !== original.stageId || this.date() !== original.date;
      return changed ? UNSAVED_CHANGES : null;
    }
    const uploading = this.photos().filter(photo => photo.state === 'uploading').length;
    if (uploading > 0) {
      return uploadCutWarning(uploading === 1 ? '1 foto' : uploading + ' fotos');
    }
    return this.text().trim() || this.photos().length > 0 ? UNSAVED_CHANGES : null;
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({ houses: this.developments.myWork(), stages: this.stagesService.listByUnit(this.unitId()) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ houses, stages }) => {
          this.houses.set(houses);
          const house = houses.find(candidate => candidate.unitId === this.unitId());
          if (!house) {
            this.loading.set(false);
            this.loadError.set('No estás asignado a esta casa.');
            return;
          }
          this.showStages(stages);
          if (this.entryId) {
            this.loadEntry(house.developmentId, this.entryId);
            return;
          }
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudo cargar la casa.'));
        },
      });
  }

  /** El avance a corregir, en su versión vigente: si ya lo corrigió otra persona, no hay qué corregir acá. */
  private loadEntry(developmentId: string, entryId: string): void {
    this.progress.list(developmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: entries => {
        this.loading.set(false);
        const entry = entries.find(candidate => candidate.id === entryId && candidate.current && candidate.unitId === this.unitId());
        if (!entry) {
          this.loadError.set('Este avance ya no se puede corregir: puede que ya tenga una versión más nueva. Corregí esa desde "Mis publicaciones".');
          return;
        }
        this.fillFrom(entry);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudo cargar el avance.'));
      },
    });
  }

  private fillFrom(entry: ProgressEntry): void {
    const stageId = entry.stageId && this.stages().some(stage => stage.id === entry.stageId) ? entry.stageId : '';
    this.text.set(entry.description);
    this.stageId.set(stageId);
    this.date.set(entry.entryDate);
    this.editingDate.set(entry.entryDate !== this.today);
    this.original.set({ text: entry.description.trim(), stageId, date: entry.entryDate });
  }

  /** Con una etapa nueva, el bloque de la etapa arranca de lo que la etapa tiene hoy (lo hace el bloque). */
  protected chooseStage(stageId: string): void {
    this.stageId.set(stageId);
    if (!this.stages().some(candidate => candidate.id === stageId)) {
      this.stageUpdate.set(null);
    }
  }

  protected chooseOther(stageId: string): void {
    this.chooseStage(stageId);
    this.choosingOther.set(false);
  }

  protected pickPhotos(event: Event): void {
    const input = event.target as HTMLInputElement;
    const current = this.photos();
    const { accepted, problems } = selectPhotos([...current.map(photo => photo.file), ...Array.from(input.files ?? [])]);
    input.value = '';
    const added = accepted.slice(current.length).map(file => ({
      key: this.nextKey++, file, preview: URL.createObjectURL(file),
      state: 'uploading' as const, percent: 0, mediaId: null, error: null, sector: this.defaultSector(),
    }));
    this.photos.set([...current, ...added]);
    this.problems.set(problems);
    added.forEach(photo => this.upload(photo.key));
  }

  /** El chip de la foto: el lugar queda recordado para las próximas fotos de esta casa. */
  protected chooseSector(key: number, sector: string | null): void {
    const chosen = sector || null;
    this.patchPhoto(key, { sector: chosen });
    rememberSector(this.auth.currentUser()?.id, this.unitId(), chosen);
  }

  /** El último lugar elegido, si sigue siendo un sector de la casa. */
  private defaultSector(): string | null {
    return lastSector(this.auth.currentUser()?.id, this.unitId(), this.house()?.sectors ?? []);
  }

  protected retryPhoto(key: number): void {
    this.patchPhoto(key, { state: 'uploading', percent: 0, error: null });
    this.upload(key);
  }

  protected removePhoto(key: number): void {
    this.uploads.get(key)?.unsubscribe();
    this.uploads.delete(key);
    const photo = this.photos().find(candidate => candidate.key === key);
    if (photo) {
      URL.revokeObjectURL(photo.preview);
    }
    this.photos.update(photos => photos.filter(candidate => candidate.key !== key));
  }

  /**
   * Un solo envío: el texto, los ids de las fotos ya subidas y, si se pidió, el avance y el
   * estado de la etapa. Si falla, nada quedó publicado y el formulario sigue igual.
   */
  protected publish(): void {
    const house = this.house();
    const stage = this.stage();
    if (!house || this.busy() || this.blocker()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    if (this.entryId) {
      this.saveCorrection(house, this.entryId);
      return;
    }
    this.progress.create(house.developmentId, {
      stageId: stage?.id ?? null,
      entryDate: this.date(),
      description: this.text().trim(),
      unitId: house.unitId,
      photoIds: this.photos().map(photo => photo.mediaId!),
      photoSectors: Object.fromEntries(this.photos()
        .filter(photo => photo.sector)
        .map(photo => [photo.mediaId!, photo.sector!])),
      stageUpdate: stage ? this.stageUpdate() : null,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        const update = stage ? this.stageUpdate() : null;
        this.publishedStage.set(stage && update
          ? `${stage.name} al ${update.status === 'COMPLETED' ? 100 : update.progressPercent}%`
          : null);
        this.published.set(true);
        this.houseStore.invalidate();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo publicar. Lo que escribiste sigue acá.'));
      },
    });
  }

  /** Una versión nueva del avance, con lo que tiene ahora: el servidor guarda la anterior. */
  private saveCorrection(house: AssignedHouse, entryId: string): void {
    this.progress.correct(entryId, {
      entryDate: this.date(),
      stageId: this.stageId() || null,
      description: this.text().trim(),
      unitId: house.unitId,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.published.set(true);
        this.houseStore.invalidate();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo guardar la corrección. Lo que escribiste sigue acá.'));
      },
    });
  }

  private showStages(stages: Stage[]): void {
    const ordered = [...stages].sort((a, b) => a.orderIndex - b.orderIndex);
    this.stages.set(ordered);
    const requested = ordered.find(stage => stage.id === this.requestedStageId);
    this.chooseStage((requested ?? workingStage(ordered))?.id ?? '');
    if (this.requestedStageId) {
      // De un solo uso: ya eligió la etapa; recargar o volver no la impone de nuevo.
      void this.router.navigate([], { relativeTo: this.route, queryParams: { etapa: null }, queryParamsHandling: 'merge', replaceUrl: true });
    }
  }

  private upload(key: number): void {
    const house = this.house();
    const photo = this.photos().find(candidate => candidate.key === key);
    if (!house || !photo) {
      return;
    }
    const subscription = this.progress.uploadPendingPhoto(house.developmentId, house.unitId, photo.file).subscribe({
      next: event => this.patchPhoto(key, event.kind === 'progress'
        ? { percent: event.percent }
        : { state: 'done', percent: 100, mediaId: event.mediaId }),
      error: (error: unknown) => this.patchPhoto(key, { state: 'failed', error: errorMessageOf(error, 'No subió.') }),
    });
    this.uploads.set(key, subscription);
  }

  private patchPhoto(key: number, changes: Partial<PendingPhoto>): void {
    this.photos.update(photos => photos.map(photo => photo.key === key ? { ...photo, ...changes } : photo));
  }
}
