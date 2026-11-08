import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, concatMap, forkJoin, from, of, toArray, catchError, map } from 'rxjs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MediaAsset, ProgressEntry, Stage, StageUpdatePayload } from '../../core/api/api.models';
import { AudioExtractionsService } from '../../core/api/audio-extractions.service';
import { ProgressService } from '../../core/api/progress.service';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { clearQuery, replaceQuery } from '../../core/routing/query-state';
import { LeaveGuarded, UNSAVED_CHANGES, uploadCutWarning, warnOnUnload } from '../../shared/ui/leave-guard';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { SectorChipComponent } from '../../shared/ui/sector-chip.component';
import { StageUpdateComponent } from '../../shared/ui/stage-update.component';
import { todayLocal } from '../../shared/time/local-date';
import { HistoryAsideComponent } from './history-aside.component';
import { HistoryFilter, filterEntries, historyTotals, photosWithoutPlace, stageFilters } from './history-derivations';
import { HistoryEntryComponent } from './history-entry.component';
import { MAX_PHOTOS_PER_ENTRY, photoRoom } from '../../shared/media/photo-limit';
import { countLabel } from './work-labels';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';

const MAX_PHOTOS = MAX_PHOTOS_PER_ENTRY;
const MAX_PHOTO_BYTES = 15 * 1024 * 1024;

/** Fotos de la galería agrupadas por el avance al que pertenecen, en el orden en que se subieron. */
export function photosByEntry(media: MediaAsset[]): Map<string, MediaAsset[]> {
  const groups = new Map<string, MediaAsset[]>();
  for (const asset of [...media].sort((a, b) => a.createdAt.localeCompare(b.createdAt))) {
    if (asset.kind !== 'PHOTO' || !asset.progressRootId) {
      continue;
    }
    const group = groups.get(asset.progressRootId) ?? [];
    group.push(asset);
    groups.set(asset.progressRootId, group);
  }
  return groups;
}

export interface EntriesMonth {
  /** Año y mes, por ejemplo 2026-09. */
  month: string;
  entries: ProgressEntry[];
}

/**
 * Los avances publicados agrupados por mes, del más nuevo al más viejo; en un mismo día,
 * el último publicado primero. Los recorridos 3D se administran en su pestaña.
 */
export function entriesByMonth(entries: ProgressEntry[]): EntriesMonth[] {
  const ordered = [...entries].sort((a, b) =>
    b.entryDate.localeCompare(a.entryDate) || b.createdAt.localeCompare(a.createdAt));
  const months: EntriesMonth[] = [];
  for (const entry of ordered) {
    const month = entry.entryDate.slice(0, 7);
    const last = months[months.length - 1];
    if (last?.month === month) {
      last.entries.push(entry);
    } else {
      months.push({ month, entries: [entry] });
    }
  }
  return months;
}

/** Filtra las fotos aceptables y explica por qué quedaron afuera las demás. */
export function selectPhotos(files: File[]): { accepted: File[]; problems: string[] } {
  const accepted: File[] = [];
  const problems: string[] = [];
  for (const file of files) {
    if (!file.type.startsWith('image/')) {
      problems.push(`${file.name} no es una imagen.`);
    } else if (file.size > MAX_PHOTO_BYTES) {
      problems.push(`${file.name} pesa más de 15 MB.`);
    } else if (accepted.length >= MAX_PHOTOS) {
      problems.push(`Se suben hasta ${MAX_PHOTOS} fotos por vez; ${file.name} quedó afuera.`);
    } else {
      accepted.push(file);
    }
  }
  return { accepted, problems };
}

/**
 * Historia de la obra: los avances publicados (lo que se hizo, cuándo, en qué etapa y con
 * qué fotos). El formulario se abre con "Publicar avance" o "Corregir" y se cierra al
 * guardar o cancelar. Los recorridos 3D viven en su pestaña.
 *
 * <p>Dueño y encargado publican y corrigen. Una corrección no borra: queda en el historial
 * y el comprador ve que el avance fue corregido. Las fotos sí se pueden borrar, porque una
 * foto equivocada no es historia: el dueño borra cualquiera y el encargado las suyas.
 */
@Component({
  selector: 'app-work-progress',
  imports: [ReactiveFormsModule, RouterLink, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent, SectorChipComponent, StageUpdateComponent, HistoryEntryComponent, HistoryAsideComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page progress-page">
      <header class="intro">
        <div>
          <h2>{{ scope.isHouse() ? 'Historia de la casa' : 'Historia de las obras comunes' }}</h2>
          <p class="ov-lead">
            {{ scope.isHouse() ? 'Lo que se publica acá lo ve el comprador de esta casa, en este orden.' : 'Lo que se publica acá lo ven todos los compradores del desarrollo.' }}
            Se corrige, no se borra.
          </p>
        </div>
        @if (entries().length > 0) {
          <p class="totals">{{ totals() }}</p>
        }
      </header>

      <div class="main">

      @if (audiosToReview() > 0) {
        <a class="review-banner" [routerLink]="scope.link('historia', 'audios')">
          {{ audiosToReview() === 1 ? 'Hay un audio interpretado que espera revisión' : 'Hay ' + audiosToReview() + ' audios interpretados que esperan revisión' }}.
          Revisalo antes de que se publique.
        </a>
      }

      @if (formOpen()) {
      <section class="ov-card panel" id="entry-form" aria-labelledby="new-title">
        <h2 id="new-title">{{ editing() ? 'Corregir avance' : 'Publicar un avance' }}</h2>
        @if (editing()) {
          <p class="muted">La versión anterior queda en el historial y el comprador ve que el avance fue corregido.</p>
        }
        <form [formGroup]="form" (ngSubmit)="submit()" class="form" novalidate>
          <div class="ov-field">
            <label class="ov-eyebrow" for="entry-date">Fecha</label>
            <input id="entry-date" class="ov-input" type="date" formControlName="entryDate" [max]="today" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="entry-stage">Etapa</label>
            <select id="entry-stage" class="ov-input" formControlName="stageId">
              <option value="">{{ generalLabel() }}</option>
              @for (stage of stages(); track stage.id) {
                <option [value]="stage.id">{{ stage.name }}</option>
              }
            </select>
          </div>
          <div class="ov-field wide">
            <label class="ov-eyebrow" for="entry-description">Qué se hizo</label>
            <textarea id="entry-description" class="ov-input" formControlName="description" maxlength="2000"
                      placeholder="Por ejemplo: se terminó el hormigonado de la losa del primer piso."></textarea>
          </div>
          @if (!editing() && chosenStage(); as chosen) {
            <app-stage-update class="wide" idPrefix="entry" [stage]="chosen" [disabled]="busy()"
                              [startOn]="startOnStage()" [(update)]="stageUpdate" />
          }
          @if (!editing()) {
            <div class="ov-field wide">
              <span class="ov-eyebrow">Fotos</span>
              <label class="ov-btn ov-btn--secondary file">
                {{ newPhotos().length ? countLabel(newPhotos().length, 'foto seleccionada', 'fotos seleccionadas') : 'Elegir fotos' }}
                <input type="file" accept="image/*" multiple (change)="pickNewPhotos($event)" />
              </label>
              <p class="muted">Hasta {{ maxPhotos }} fotos por avance, de hasta 15 MB cada una.</p>
              @if (newPhotos().length > 0 && sectors().length > 0) {
                <ul class="picked" aria-label="Lugar de cada foto">
                  @for (file of newPhotos(); track $index; let i = $index) {
                    <li>
                      <span class="picked-name">{{ file.name }}</span>
                      <app-sector-chip [sectors]="sectors()" [value]="newPhotoSectors()[i]"
                                       [label]="'Lugar de ' + file.name" (changed)="setNewPhotoSector(i, $event)" />
                    </li>
                  }
                </ul>
              }
            </div>
          }

          @for (problem of problems(); track problem) {
            <p class="ov-error wide">{{ problem }}</p>
          }
          @if (formError(); as message) {
            <p class="ov-error wide" role="alert">{{ message }}</p>
          }

          <div class="actions wide">
            <button class="ov-btn ov-btn--primary" type="submit" [disabled]="form.invalid || busy()">
              {{ busy() ? busyLabel() : (editing() ? 'Guardar corrección' : 'Publicar avance') }}
            </button>
            <button class="ov-link" type="button" [disabled]="busy()" (click)="closeForm()">Cancelar</button>
          </div>
        </form>
      </section>
      } @else {
        <!-- Con el formulario cerrado, lo que falló al agregar fotos se avisa igual. -->
        @for (problem of problems(); track problem) {
          <p class="ov-error">{{ problem }}</p>
        }
      }

      @if (loading()) {
        <app-loading-state label="Cargando avances…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (timeline().length === 0) {
        @if (entries().length === 0) {
          <app-empty-state title="Todavía no hay avances publicados"
            [message]="scope.isHouse() ? 'Cada avance de esta casa aparece en la historia que ve su comprador.' : 'Las novedades de calles, redes y espacios comunes llegan a todos los compradores.'">
            @if (!formOpen()) {
              <button class="ov-btn ov-btn--primary" type="button" (click)="openForm()">Publicar avance</button>
            }
          </app-empty-state>
        } @else {
          <p class="muted">No hay avances con este filtro.</p>
        }
      } @else {
        @for (group of timeline(); track group.month) {
          <section class="month" [attr.aria-label]="monthTitle(group.month)">
            <h3 class="month-title">{{ monthTitle(group.month) }} <span class="count">· {{ countLabel(group.entries.length, 'avance', 'avances') }}</span></h3>
            <ol class="ov-card entries">
              @for (entry of group.entries; track entry.id) {
                <li>
                  <app-history-entry [entry]="entry" [stageName]="stageName(entry)" [generalLabel]="generalLabel()"
                                     [photos]="photos().get(entry.rootId) ?? []" [sectors]="sectors()" [owner]="store.isOwner()"
                                     [busyPhotoId]="busyPhoto()" [uploading]="uploadingFor() === entry.id" [uploadsBusy]="uploadingFor() !== null"
                                     (correct)="startEdit($event)" (addPhotos)="addPhotos(entry, $event)"
                                     (movePhoto)="movePhoto($event.photo, $event.sector)" (deletePhoto)="deletePhoto($event)" />
                </li>
              }
            </ol>
          </section>
        }
      }
      </div>

      <app-history-aside class="aside" [filters]="filters()" [filter]="filter()" [unplaced]="unplaced().length"
                         [onlyUnplaced]="onlyUnplaced()" [audioLink]="scope.link('historia', 'audios')"
                         [comparisonsLink]="store.isOwner() ? scope.link('historia', 'comparaciones') : null"
                         (publish)="openForm()" (filterChange)="chooseFilter($event)" (unplacedToggle)="toggleUnplaced()" />
    </div>
  `,
  styles: `
    .progress-page {
      display: grid;
      grid-template-areas: 'head aside' 'main aside';
      grid-template-columns: minmax(0, 1fr) 18rem;
      grid-template-rows: auto 1fr;
      align-items: start;
      gap: var(--ov-space-5) var(--ov-space-6);
      padding-top: var(--ov-space-6);
    }

    .intro {
      grid-area: head;
    }

    .main {
      grid-area: main;
    }

    .aside {
      grid-area: aside;
    }

    .main {
      display: grid;
      gap: var(--ov-space-5);
      min-width: 0;
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
    }

    .intro .ov-lead {
      margin-top: var(--ov-space-1);
    }

    .totals,
    .muted,
    .count {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-regular);
    }

    .review-banner {
      padding: var(--ov-space-4) var(--ov-space-5);
      border: 1px solid var(--ov-status-delayed);
      border-radius: var(--ov-radius-md);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-ink);
      font-weight: var(--ov-weight-medium);
      text-decoration: none;
    }

    .month {
      display: grid;
      gap: var(--ov-space-2);
    }

    .month-title {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .entries {
      margin: 0;
      padding: 0;
      overflow: hidden;
      list-style: none;
    }

    .entries > li + li {
      border-top: 1px solid var(--ov-line);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .file {
      position: relative;
      overflow: hidden;
      justify-self: start;
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

    .picked {
      display: grid;
      gap: var(--ov-space-2);
      margin: var(--ov-space-3) 0 0;
      padding: 0;
      list-style: none;
    }

    .picked li {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(9rem, 14rem);
      align-items: center;
      gap: var(--ov-space-3);
    }

    .picked-name {
      overflow: hidden;
      font-size: var(--ov-text-sm);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    @media (min-width: 48rem) {
      .form {
        grid-template-columns: 12rem 1fr;
      }
    }

    /* Hasta tablet, el costado (publicar, filtro, fotos sin lugar) va entre el título y la historia. */
    @media (max-width: 64rem) {
      .progress-page {
        grid-template-areas: 'head' 'aside' 'main';
        grid-template-columns: minmax(0, 1fr);
        grid-template-rows: auto;
      }
    }
  `,
})
export class WorkProgressComponent implements OnInit, LeaveGuarded {

  protected readonly store = inject(WorkStore);
  protected readonly scope = injectWorkScope();
  private readonly progress = inject(ProgressService);
  private readonly units = inject(UnitsService);
  private readonly audioService = inject(AudioExtractionsService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  /** Novedades trae acá con ?corregir=<avance>: se abre su corrección una sola vez. */
  private correctionRequested = this.route.snapshot.queryParamMap.get('corregir');
  /** "Actualizar avance" desde Etapas: abre el formulario con esa etapa y su bloque prendido. */
  private publishRequested = this.route.snapshot.queryParamMap.get('etapa');
  private readonly router = inject(Router);

  protected readonly today = todayLocal();
  protected readonly maxPhotos = MAX_PHOTOS;
  protected readonly countLabel = countLabel;

  protected readonly entries = signal<ProgressEntry[]>([]);
  protected readonly stages = signal<Stage[]>([]);
  private readonly media = signal<MediaAsset[]>([]);
  protected readonly photos = computed(() => photosByEntry(this.media()));
  protected readonly filter = signal<HistoryFilter>(this.route.snapshot.queryParamMap.get('filtro') ?? 'all');
  protected readonly onlyUnplaced = signal(this.route.snapshot.queryParamMap.get('sinlugar') === '1');
  protected readonly unplaced = computed(() => photosWithoutPlace(this.media(), this.entries()));
  protected readonly filters = computed(() => stageFilters(this.entries(), this.stages(), this.generalLabel()));
  protected readonly totals = computed(() => historyTotals(this.entries(), this.media()));
  protected readonly timeline = computed(() => {
    const unplacedRoots = this.onlyUnplaced()
      ? new Set(this.unplaced().map(photo => photo.progressRootId!))
      : null;
    return entriesByMonth(filterEntries(this.entries(), this.filter(), unplacedRoots));
  });
  /** Cerrado por defecto: se abre para publicar o corregir y se cierra al terminar. */
  protected readonly formOpen = signal(false);
  protected readonly audiosToReview = signal(0);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly problems = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly busyLabel = signal('Publicando…');
  protected readonly editing = signal<ProgressEntry | null>(null);
  protected readonly newPhotos = signal<File[]>([]);
  /** El lugar de cada foto elegida, por posición. Sólo en una casa: lo común no tiene sectores. */
  protected readonly newPhotoSectors = signal<(string | null)[]>([]);
  protected readonly sectors = signal<string[]>([]);
  protected readonly busyPhoto = signal<string | null>(null);
  protected readonly uploadingFor = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    entryDate: [todayLocal(), Validators.required],
    stageId: [''],
    description: ['', [Validators.required, Validators.maxLength(2000)]],
  });

  private readonly formStageId = toSignal(this.form.controls.stageId.valueChanges, { initialValue: '' });
  protected readonly chosenStage = computed(() => this.stages().find(stage => stage.id === this.formStageId()) ?? null);
  /** El cambio de la etapa que viaja con el avance nuevo; nulo si no se actualiza. */
  protected readonly stageUpdate = signal<StageUpdatePayload | null>(null);
  protected readonly startOnStage = signal(false);
  /** Llegó desde Etapas: al publicar vuelve ahí. */
  private returnToStages = false;

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  protected generalLabel(): string {
    return this.scope.isHouse() ? 'General de la casa' : 'General';
  }

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  /** El avance a medio escribir (nuevo o corrección) y las fotos que se están agregando. */
  leaveWarning(): string | null {
    if (this.uploadingFor()) {
      return uploadCutWarning('las fotos');
    }
    return this.formOpen() && (this.form.dirty || this.newPhotos().length > 0) ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    this.load();
    this.loadAudios();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      entries: this.progress.list(this.developmentId),
      stages: this.scope.stages(),
      media: this.progress.media(this.developmentId),
      sectors: this.scope.isHouse() && this.scope.unitId() ? this.units.sectors(this.scope.unitId()!) : of<string[]>([]),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ entries, stages, media, sectors }) => {
          this.sectors.set(sectors);
          // Sólo lo de este lugar: la casa abierta, o lo común.
          this.entries.set(entries.filter(entry => this.scope.owns(entry.unitId)));
          this.stages.set(stages);
          this.media.set(media);
          this.loading.set(false);
          this.openRequestedCorrection();
          this.openRequestedPublish();
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los avances.'));
        },
      });
  }

  /** Los audios pendientes son un aviso: si no cargan, la historia se muestra igual. */
  private loadAudios(): void {
    this.audioService.listByDevelopment(this.developmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: extractions => this.audiosToReview.set(
          extractions.filter(extraction => extraction.reviewStatus === 'READY_FOR_REVIEW' && this.scope.owns(extraction.unitId)).length),
        error: () => this.audiosToReview.set(0),
      });
  }

  /** El filtro vive en la dirección (`?filtro=&sinlugar=1`): ir a una foto o a un parte y volver lo conserva. */
  protected chooseFilter(filter: HistoryFilter): void {
    this.filter.set(filter);
    replaceQuery(this.router, this.route, { filtro: filter === 'all' ? null : filter });
  }

  /** "Asignar lugares": sólo los avances con fotos sin lugar, donde cada foto tiene su chip. */
  protected toggleUnplaced(): void {
    this.onlyUnplaced.update(only => !only);
    replaceQuery(this.router, this.route, { sinlugar: this.onlyUnplaced() ? '1' : null });
  }

  protected monthTitle(month: string): string {
    const [year, number] = month.split('-');
    const names = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto',
      'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];
    return `${names[Number(number) - 1]} ${year}`;
  }

  protected stageName(entry: ProgressEntry): string | null {
    return this.stages().find(stage => stage.id === entry.stageId)?.name ?? null;
  }

  protected pickNewPhotos(event: Event): void {
    const input = event.target as HTMLInputElement;
    const { accepted, problems } = selectPhotos(Array.from(input.files ?? []));
    input.value = '';
    this.newPhotos.set(accepted);
    this.newPhotoSectors.set(accepted.map(() => null));
    this.problems.set(problems);
  }

  protected setNewPhotoSector(index: number, sector: string | null): void {
    this.newPhotoSectors.update(sectors => sectors.map((current, i) => i === index ? sector : current));
  }

  /** Pasa una foto publicada a otro lugar de la casa; sin lugar, queda en "General". */
  protected movePhoto(photo: MediaAsset, sector: string | null): void {
    this.busyPhoto.set(photo.id);
    this.progress.updateMediaSector(photo.id, sector).subscribe({
      next: updated => {
        this.busyPhoto.set(null);
        this.media.update(media => media.map(item => item.id === updated.id ? { ...item, sector: updated.sector } : item));
      },
      error: (error: unknown) => {
        this.busyPhoto.set(null);
        this.problems.set([errorMessageOf(error, 'No se pudo cambiar el lugar de la foto.')]);
      },
    });
  }

  /** "Publicar avance": un avance nuevo. Si se estaba corrigiendo otro, se deja de corregir. */
  protected openForm(): void {
    if (this.editing()) {
      this.editing.set(null);
      this.resetForm();
    }
    this.formError.set(null);
    this.problems.set([]);
    this.formOpen.set(true);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    this.editing.set(null);
    this.formError.set(null);
    this.problems.set([]);
    this.resetForm();
  }

  private openRequestedCorrection(): void {
    const requested = this.correctionRequested;
    this.correctionRequested = null;
    if (requested) {
      clearQuery(this.router, this.route, 'corregir');
    }
    const entry = requested ? this.entries().find(candidate => candidate.id === requested) : undefined;
    if (entry) {
      this.startEdit(entry);
    }
  }

  private openRequestedPublish(): void {
    const requested = this.publishRequested;
    this.publishRequested = null;
    if (requested) {
      clearQuery(this.router, this.route, 'etapa');
    }
    if (!requested || !this.stages().some(stage => stage.id === requested)) {
      return;
    }
    this.openForm();
    this.startOnStage.set(true);
    this.returnToStages = true;
    this.form.controls.stageId.setValue(requested);
  }

  protected startEdit(entry: ProgressEntry): void {
    this.editing.set(entry);
    this.formError.set(null);
    this.problems.set([]);
    this.form.setValue({ entryDate: entry.entryDate, stageId: entry.stageId ?? '', description: entry.description });
    this.formOpen.set(true);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected submit(): void {
    const value = this.form.getRawValue();
    const payload = {
      entryDate: value.entryDate,
      stageId: value.stageId || null,
      description: value.description.trim(),
      unitId: this.scope.unitId(),
    };
    if (!payload.description) {
      return;
    }
    this.busy.set(true);
    this.formError.set(null);

    const editing = this.editing();
    if (editing) {
      this.busyLabel.set('Guardando…');
      this.progress.correct(editing.id, payload).subscribe({
        next: () => {
          this.busy.set(false);
          this.closeForm();
          this.load();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.formError.set(errorMessageOf(error, 'No se pudo guardar la corrección.'));
        },
      });
      return;
    }

    this.busyLabel.set('Publicando…');
    // El avance y el cambio de la etapa salen juntos o no sale ninguno.
    const stageUpdate = payload.stageId ? this.stageUpdate() : null;
    this.progress.create(this.developmentId, stageUpdate ? { ...payload, stageUpdate } : payload).subscribe({
      next: entry => {
        const files = this.newPhotos();
        if (files.length === 0) {
          this.finishPublish([]);
          return;
        }
        this.busyLabel.set(`Subiendo ${files.length} ${files.length === 1 ? 'foto' : 'fotos'}…`);
        this.uploadAll(entry, files, this.newPhotoSectors()).subscribe(failures => this.finishPublish(failures));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.formError.set(errorMessageOf(error, 'No se pudo publicar el avance.'));
      },
    });
  }

  /**
   * "Agregar fotos" a un avance publicado: sube sólo las que entran hasta el tope del avance (el
   * servidor rechaza la que pase), y dice cuántas quedaron afuera.
   */
  protected addPhotos(entry: ProgressEntry, event: Event): void {
    const input = event.target as HTMLInputElement;
    const { accepted, problems } = selectPhotos(Array.from(input.files ?? []));
    input.value = '';
    const room = photoRoom((this.photos().get(entry.rootId) ?? []).filter(photo => photo.kind === 'PHOTO').length);
    const taken = accepted.slice(0, room);
    if (accepted.length > room) {
      const left = accepted.length - room;
      problems.push(`Un avance lleva hasta ${MAX_PHOTOS} fotos: ${left === 1 ? 'quedó afuera 1' : 'quedaron afuera ' + left}.`);
    }
    this.problems.set(problems);
    if (taken.length === 0) {
      return;
    }
    this.uploadingFor.set(entry.id);
    this.uploadAll(entry, taken).subscribe(failures => {
      this.uploadingFor.set(null);
      this.problems.set([...problems, ...failures]);
      this.reloadMedia();
    });
  }

  protected deletePhoto(photo: MediaAsset): void {
    if (!window.confirm('¿Borrar esta foto? Deja de verse en la historia de la obra.')) {
      return;
    }
    this.progress.deleteMedia(photo.id).subscribe({
      next: () => this.media.update(media => media.filter(item => item.id !== photo.id)),
      error: (error: unknown) => this.problems.set([errorMessageOf(error, 'No se pudo borrar la foto.')]),
    });
  }

  /**
   * Sube las fotos de a una. Si alguna falla sigue con las demás: perder diez fotos por
   * una que no subió sería peor que avisar cuál faltó.
   */
  private uploadAll(entry: ProgressEntry, files: File[], sectors: (string | null)[] = []): Observable<string[]> {
    return from(files.map((file, i) => ({ file, sector: sectors[i] ?? null }))).pipe(
      concatMap(({ file, sector }) => this.progress.uploadPhoto(this.developmentId, entry.id, file, sector).pipe(
        map(() => null as string | null),
        catchError((error: unknown) => of(`${file.name}: ${errorMessageOf(error, 'no se pudo subir.')}`)),
      )),
      toArray(),
      map(results => results.filter((result): result is string => result !== null)),
    );
  }

  private finishPublish(failures: string[]): void {
    this.busy.set(false);
    this.scope.refresh();
    if (this.returnToStages && failures.length === 0) {
      this.returnToStages = false;
      // Publicado: el formulario se cierra antes de irse, así no avisa de cambios sin guardar.
      this.closeForm();
      void this.router.navigate(this.scope.link('etapas'));
      return;
    }
    // Se cierra igual: el aviso de las fotos que no subieron queda visible arriba de la lista.
    this.closeForm();
    this.problems.set(failures.length > 0 ? ['El avance se publicó, pero algunas fotos no subieron:', ...failures] : []);
    this.load();
  }

  private resetForm(): void {
    this.startOnStage.set(false);
    this.stageUpdate.set(null);
    this.form.reset({ entryDate: todayLocal(), stageId: '', description: '' });
    this.newPhotos.set([]);
    this.newPhotoSectors.set([]);
  }

  private reloadMedia(): void {
    this.progress.media(this.developmentId).subscribe({
      next: media => this.media.set(media),
      error: () => {},
    });
  }
}
