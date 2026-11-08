import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, catchError, forkJoin, map, of, switchMap, takeWhile, timer } from 'rxjs';
import { Capture3d, CaptureProcessing, CaptureUpdatePayload, MediaAsset, ProgressEntry, SectorView, Stage } from '../../core/api/api.models';
import { CapturesService } from '../../core/api/captures.service';
import { ProgressService } from '../../core/api/progress.service';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { replaceQuery } from '../../core/routing/query-state';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { isDiscardable, processingLabel } from './capture-labels';
import { PlaceDetailComponent } from './place-detail.component';
import { latestCell, placeGrid } from './place-grid';
import { PlaceGridComponent, PlaceSelection } from './place-grid.component';
import { ReconstructionCreditsComponent } from './reconstruction-credits.component';
import { injectWorkScope } from './work-scope';
import { SectorsEditorComponent } from './sectors-editor.component';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

const POLL_INTERVAL_MS = 10_000;
/** Treinta minutos: la reconstrucción externa tarda entre 7 y 20. */
const MAX_POLLS = 180;
const MAX_VIDEO_BYTES = 2 * 1024 * 1024 * 1024;
/** Cuántos meses muestra la grilla de entrada; los anteriores se piden. */
const VISIBLE_MONTHS = 6;

export { isDiscardable, processingLabel } from './capture-labels';

/**
 * "Lugares y 3D": una grilla de lugares por mes con lo que se
 * capturó de cada uno, el detalle de la celda elegida con sus acciones (mover, borrar, editar
 * los datos del recorrido) y, para el dueño, los créditos de reconstrucción que quedan. En las
 * obras comunes, la misma pantalla sin lugares definidos. El video suelto queda plegado.
 *
 * <p>Dueño y encargado suben videos y corrigen los datos de cada captura. Con el servicio
 * real de reconstrucción cada video consume un crédito, así que la subida pide una
 * confirmación explícita. La alineación entre capturas es del dueño y se hace en el visor.
 */
/** La celda pedida en la dirección; si falta una parte, ninguna. */
function placeFromQuery(name: string | null, month: string | null): PlaceSelection | null {
  return name && month ? { name, month } : null;
}

@Component({
  selector: 'app-work-captures',
  imports: [ShortDatePipe, 
    ReactiveFormsModule, RouterLink, LoadingStateComponent, ErrorStateComponent, SectorsEditorComponent,
    PlaceGridComponent, PlaceDetailComponent, ReconstructionCreditsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page captures-page">
      <header class="intro">
        <div class="intro-text">
          <h2>{{ scope.isHouse() ? 'Lugares y 3D' : 'Recorridos 3D de las obras comunes' }}</h2>
          <p class="ov-lead">
            {{ scope.isHouse()
              ? 'Qué se capturó de cada lugar, mes a mes. Es lo que el comprador recorre en "lugar por lugar" y en 3D.'
              : 'Los recorridos de calles, redes y espacios comunes los ven todos los compradores del desarrollo.' }}
          </p>
        </div>
        <div class="block-actions">
          @if (scope.isHouse() && store.isOwner() && !editingSectors()) {
            <button class="ov-btn ov-btn--secondary" type="button" (click)="editingSectors.set(true)">
              {{ sectors().length === 0 ? 'Definir lugares' : 'Cambiar lugares' }}
            </button>
          }
          @if (available().length > 0) {
            <a class="ov-btn ov-btn--secondary" [routerLink]="scope.link('recorridos', 'visor')">
              {{ store.isOwner() ? 'Alinear en el visor' : 'Abrir el visor' }}
            </a>
          }
          @if (scope.isHouse() && sectors().length > 0) {
            <a class="ov-btn ov-btn--primary" [routerLink]="['/hoy', scope.unitId(), 'recorrido']">Recorrido de hoy</a>
          }
        </div>
        <details class="ov-disclosure guide">
          <summary>Cómo filmar para que el 3D salga bien</summary>
          <ul>
            <li>Caminá despacio durante uno a tres minutos, sin cortes.</li>
            <li>Recorré el ambiente desde distintas posiciones y alturas, como si lo rodearas.</li>
            <li>Evitá movimientos bruscos, zonas muy oscuras y gente cruzando.</li>
            <li>Un video por ambiente: la cocina, un dormitorio, la fachada.</li>
          </ul>
        </details>
      </header>

      @if (editingSectors() && scope.isHouse() && store.isOwner()) {
        <section class="ov-card panel">
          <app-sectors-editor [unitId]="scope.unitId() ?? ''" [sectors]="sectors()"
                              (saved)="sectorsSaved()" (closed)="editingSectors.set(false)" />
        </section>
      }

      @if (actionError(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }

      @if (loading()) {
        <app-loading-state label="Cargando recorridos…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else {
        @if (stuck().length > 0) {
          <section aria-labelledby="processing-title">
            <h3 id="processing-title">No avanzan</h3>
            <ul class="list" aria-live="polite">
              @for (capture of stuck(); track capture.captureId) {
                <li class="row">
                  <div>
                    <p class="row-title">{{ capture.zone ?? capture.description ?? 'Recorrido' }}</p>
                    <p class="muted">Filmado el {{ capture.captureDate | shortDate: 'd MMM y' }}</p>
                  </div>
                  <div class="row-status">
                    <span class="pill-alert">{{ processingLabel(capture) }}</span>
                    @if (capture.failureReason) {
                      <p class="muted">{{ capture.failureReason }}</p>
                    }
                    @if (store.isOwner() || capture.mine) {
                      <button class="ov-link" type="button" (click)="discard(capture)">Descartar</button>
                    }
                  </div>
                </li>
              }
            </ul>
          </section>
        }

        @if (grid().rows.length === 0) {
          <p class="ov-card panel muted">
            @if (!scope.isHouse()) {
              Todavía no hay recorridos de las obras comunes.
            } @else if (store.isOwner()) {
              Todavía no hay lugares definidos: definilos para grabar la casa por lugares.
            } @else {
              El responsable todavía no definió los lugares de esta casa.
            }
          </p>
        } @else {
          @if (hiddenMonths() > 0) {
            <button type="button" class="ov-link older" (click)="allMonths.set(true)">
              Ver {{ hiddenMonths() === 1 ? 'el mes anterior' : 'los ' + hiddenMonths() + ' meses anteriores' }}
            </button>
          }
          <app-place-grid [grid]="grid()" [months]="visibleMonths()" [selected]="selection()" (select)="choosePlace($event)" />
        }

        <div class="below">
          @if (selectedCell(); as chosen) {
            <app-place-detail class="detail" [name]="chosen.name" [cell]="chosen.cell" [sectors]="sectors()" [stages]="stages()"
                              [owner]="store.isOwner()" [busyMediaId]="busyMedia()" [today]="today"
                              [viewerLink]="scope.link('recorridos', 'visor')"
                              (moveMedia)="moveMedia($event.asset, $event.sector)" (deleteMedia)="deleteMedia($event)"
                              (saveCapture)="saveCapture($event.capture, $event.changes)" />
          }
          @if (store.isOwner()) {
            <app-reconstruction-credits class="credits" [simulated]="simulated() === true" [credits]="credits()"
                                        [loaded]="creditsLoaded()" (upload)="openUpload()" />
          }
        </div>
      }

      <!-- Plegado por defecto: lo habitual es grabar por sectores. El aviso de créditos y su confirmación no cambian. -->
      <details #upload id="upload" class="ov-disclosure ov-disclosure--wide ov-card panel upload">
        <summary class="panel-head">
          <h3 id="upload-title">{{ scope.isHouse() ? 'Subir un video suelto' : 'Subir el video de un recorrido' }}</h3>
          @if (simulated() !== null) {
            <span [class]="simulated() ? 'ov-pill ov-pill--accent' : 'pill-alert'">
              {{ simulated() ? 'Reconstrucción simulada' : 'Consume créditos' }}
            </span>
          }
        </summary>

        <form [formGroup]="form" (ngSubmit)="submit()" class="form" novalidate>
          <div class="ov-field wide">
            <span class="ov-eyebrow">Video</span>
            <label class="ov-btn ov-btn--secondary file">
              {{ file()?.name ?? 'Elegir video' }}
              <input type="file" accept="video/*" (change)="pickFile($event)" [disabled]="uploading()" />
            </label>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="capture-date">Fecha de la filmación</label>
            <input id="capture-date" class="ov-input" type="date" formControlName="captureDate" [max]="today" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="capture-zone">Ambiente</label>
            <input id="capture-zone" class="ov-input" formControlName="zone" list="zones" maxlength="80"
                   placeholder="Por ejemplo: Living + cocina" />
            <datalist id="zones">
              @for (zone of zones(); track zone) {
                <option [value]="zone"></option>
              }
            </datalist>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="capture-stage">Etapa</label>
            <select id="capture-stage" class="ov-input" formControlName="stageId">
              <option value="">Sin etapa</option>
              @for (stage of stages(); track stage.id) {
                <option [value]="stage.id">{{ stage.name }}</option>
              }
            </select>
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="capture-description">Descripción</label>
            <input id="capture-description" class="ov-input" formControlName="description" maxlength="500"
                   placeholder="Opcional" />
          </div>

          @if (!simulated() && simulated() !== null) {
            <label class="confirm wide">
              <input type="checkbox" formControlName="confirmCredit" />
              Entiendo que este video consume un crédito del servicio de reconstrucción, y que es la toma definitiva.
            </label>
          }

          @if (formError(); as message) {
            <p class="ov-error wide" role="alert">{{ message }}</p>
          }

          <div class="actions wide">
            <button class="ov-btn ov-btn--primary" type="submit" [disabled]="!canSubmit()">
              {{ uploading() ? 'Subiendo video…' : 'Subir y reconstruir' }}
            </button>
            @if (uploading()) {
              <span class="muted">No cierres la pestaña hasta que termine la subida.</span>
            }
          </div>
        </form>
      </details>
    </div>
  `,
  styles: `
    .intro {
      display: grid;
      gap: var(--ov-space-3);
    }

    .captures-page {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    /* La tabla de la grilla es más ancha que el teléfono: se corre adentro, no estira la página. */
    .captures-page > * {
      min-width: 0;
    }

    .intro {
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: end;
    }

    .intro .guide {
      grid-column: 1 / -1;
    }

    @media (max-width: 48rem) {
      .intro {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .panel {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-6);
    }

    .panel-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .block-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3);
    }

    /* Debajo de la grilla: el detalle de la celda y, para el dueño, los créditos. */
    .below {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-5);
    }

    .detail {
      flex: 2 1 32rem;
      min-width: 0;
    }

    .credits {
      flex: 1 1 16rem;
    }

    .older {
      justify-self: start;
    }

    /* Una alerta en el desarrollista va en ámbar, siempre con su texto. */
    .pill-alert {
      display: inline-flex;
      align-items: center;
      min-height: 1.625rem;
      padding: 0 var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .upload > summary::after {
      color: var(--ov-ink-muted);
    }

    .guide {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .guide summary {
      color: var(--ov-ink);
      cursor: pointer;
    }

    .guide ul {
      margin: var(--ov-space-2) 0 0;
      padding-left: var(--ov-space-5);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .file {
      position: relative;
      overflow: hidden;
      justify-self: start;
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

    .confirm {
      display: flex;
      gap: var(--ov-space-3);
      align-items: flex-start;
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      /* Un aviso, no un error: ámbar, como el chip "Consume créditos". */
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    h3 {
      font-size: var(--ov-text-lg);
    }

    section > h3,
    .panel-head h3 {
      margin: 0;
    }

    section:not(.panel) > h3 {
      margin-bottom: var(--ov-space-3);
    }

    .list {
      margin: var(--ov-space-3) 0 0;
      padding: 0;
      list-style: none;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) 0;
      border-top: 1px solid var(--ov-line);
    }

    .row-title {
      font-weight: var(--ov-weight-medium);
    }

    .row-status {
      display: grid;
      justify-items: end;
      gap: var(--ov-space-1);
      max-width: 28rem;
      text-align: right;
    }

    .edit {
      width: 100%;
    }

    @media (min-width: 48rem) {
      .form {
        grid-template-columns: 1fr 1fr;
      }
    }
  `,
})
export class WorkCapturesComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  private readonly captures = inject(CapturesService);
  private readonly units = inject(UnitsService);
  private readonly progress = inject(ProgressService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly today = todayLocal();
  protected readonly processingLabel = processingLabel;
  protected readonly isDiscardable = isDiscardable;

  protected readonly available = signal<Capture3d[]>([]);
  protected readonly processing = signal<CaptureProcessing[]>([]);
  protected readonly stages = signal<Stage[]>([]);
  protected readonly simulated = signal<boolean | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly uploading = signal(false);
  protected readonly file = signal<File | null>(null);
  /** Errores de "Editar datos" y "Descartar": se ven aunque la subida esté plegada. */
  protected readonly actionError = signal<string | null>(null);
  /** Lugares definidos para la casa, con el frente; en las obras comunes no hay. */
  private readonly sectorViews = signal<SectorView[]>([]);
  protected readonly sectors = computed(() => this.sectorViews().map(sector => sector.name));
  private readonly media = signal<MediaAsset[]>([]);
  private readonly entries = signal<ProgressEntry[]>([]);
  protected readonly credits = signal<number | null>(null);
  protected readonly creditsLoaded = signal(false);
  private readonly uploadPanel = viewChild<ElementRef<HTMLDetailsElement>>('upload');
  protected readonly busyMedia = signal<string | null>(null);
  /** Abierto de entrada con ?sectores=editar: el aviso de "Ver como el comprador" trae acá. */
  protected readonly editingSectors = signal(inject(ActivatedRoute).snapshot.queryParamMap.get('sectores') === 'editar');
  protected readonly grid = computed(() => placeGrid(
    this.sectorViews(), this.available(), this.processing(), this.media(), this.entries(), this.scope.unitId()));
  /** Los que fallaron o cuya subida quedó a medias: no avanzan, se descartan. */
  protected readonly stuck = computed(() => this.processing().filter(capture => isDiscardable(capture)));
  /** Se muestran los seis meses más nuevos; los anteriores, a pedido. */
  protected readonly allMonths = signal(false);
  protected readonly visibleMonths = computed(() => this.allMonths() ? this.grid().months : this.grid().months.slice(-VISIBLE_MONTHS));
  protected readonly hiddenMonths = computed(() => this.grid().months.length - this.visibleMonths().length);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  /** La celda elegida vive en la dirección (`?lugar=&mes=`): abrir el visor y volver la conserva. */
  protected readonly selected = signal<PlaceSelection | null>(
    placeFromQuery(this.route.snapshot.queryParamMap.get('lugar'), this.route.snapshot.queryParamMap.get('mes')));

  protected choosePlace(place: PlaceSelection): void {
    this.selected.set(place);
    replaceQuery(this.router, this.route, { lugar: place.name, mes: place.month });
  }
  /** La celda elegida o, al entrar, la más nueva con material. */
  protected readonly selection = computed<PlaceSelection | null>(() => {
    const chosen = this.selected();
    if (chosen && this.grid().rows.some(row => row.name === chosen.name) && this.grid().months.includes(chosen.month)) {
      return chosen;
    }
    const newest = this.grid().rows
      .map(row => ({ name: row.name, cell: latestCell(row) }))
      .filter((item): item is { name: string; cell: NonNullable<ReturnType<typeof latestCell>> } => item.cell !== null)
      .sort((a, b) => b.cell.month.localeCompare(a.cell.month))[0];
    return newest ? { name: newest.name, month: newest.cell.month } : null;
  });
  protected readonly selectedCell = computed(() => {
    const chosen = this.selection();
    const row = chosen ? this.grid().rows.find(candidate => candidate.name === chosen.name) : undefined;
    const index = chosen ? this.grid().months.indexOf(chosen.month) : -1;
    return row && index >= 0 ? { name: row.name, cell: row.cells[index] } : null;
  });
  private readonly formValid = signal(false);
  private polling: Subscription | null = null;

  protected readonly zones = computed(() => {
    const names = [...this.available().map(c => c.zone), ...this.processing().map(c => c.zone)]
      .filter((zone): zone is string => !!zone);
    return [...new Set(names)];
  });

  protected readonly form = this.formBuilder.nonNullable.group({
    captureDate: [todayLocal(), Validators.required],
    zone: [''],
    stageId: [''],
    description: [''],
    confirmCredit: [false],
  });

  protected readonly canSubmit = computed(() =>
    !this.uploading() && this.file() !== null && this.formValid() && this.simulated() !== null);

  protected readonly scope = injectWorkScope();

  private get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.updateValidity());
  }

  ngOnInit(): void {
    this.captures.reconstructionMode().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: mode => {
        this.simulated.set(mode.simulated);
        this.updateValidity();
      },
      // Sin saber el modo no se ofrece subir: más vale no enviar que gastar un crédito sin aviso.
      error: () => this.formError.set('No se pudo verificar el modo de reconstrucción. Recargá la página para subir videos.'),
    });
    this.load();
    this.loadCredits();
  }

  /** Los créditos son del dueño y son un agregado: si no cargan, la pantalla sigue igual. */
  private loadCredits(): void {
    if (!this.store.isOwner()) {
      return;
    }
    this.captures.reconstructionCredits().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: answer => {
        this.credits.set(answer.credits);
        this.creditsLoaded.set(true);
      },
      error: () => this.creditsLoaded.set(true),
    });
  }

  protected openUpload(): void {
    const panel = this.uploadPanel()?.nativeElement;
    if (panel) {
      panel.open = true;
      panel.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    }
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const unitId = this.scope.unitId();
    forkJoin({
      available: this.captures.listByDevelopment(this.developmentId),
      processing: this.captures.inProcess(this.developmentId),
      stages: this.scope.stages(),
      sectors: unitId ? this.units.sectorDetail(unitId) : of<SectorView[]>([]),
      media: unitId ? this.progress.media(this.developmentId) : of<MediaAsset[]>([]),
      // La fecha de una foto de un avance es la del avance; sin avances, las fotos caen en su día de subida.
      entries: unitId ? this.progress.list(this.developmentId).pipe(catchError(() => of<ProgressEntry[]>([]))) : of<ProgressEntry[]>([]),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ available, processing, stages, sectors, media, entries }) => {
          this.media.set(media);
          this.entries.set(entries);
          // Sólo los recorridos de este lugar: la casa abierta, o las obras comunes.
          this.available.set(available.filter(capture => this.scope.owns(capture.unitId)));
          this.processing.set(processing.filter(capture => this.scope.owns(capture.unitId)));
          this.stages.set(stages);
          this.sectorViews.set(sectors);
          this.loading.set(false);
          this.watchProcessing();
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los recorridos.'));
        },
      });
  }

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const picked = input.files?.[0] ?? null;
    input.value = '';
    this.formError.set(null);
    if (picked && !picked.type.startsWith('video/')) {
      this.formError.set('El archivo tiene que ser un video.');
      this.file.set(null);
    } else if (picked && picked.size > MAX_VIDEO_BYTES) {
      this.formError.set('El video pesa más de 2 GB. Filmá un recorrido más corto, de uno a tres minutos.');
      this.file.set(null);
    } else {
      this.file.set(picked);
    }
    this.updateValidity();
  }

  protected submit(): void {
    const file = this.file();
    if (!file || !this.canSubmit()) {
      return;
    }
    const value = this.form.getRawValue();
    this.uploading.set(true);
    this.formError.set(null);

    this.captures.uploadVideo(this.developmentId, {
      captureDate: value.captureDate,
      zone: value.zone.trim() || null,
      unitId: this.scope.unitId(),
      stageId: value.stageId || null,
      description: value.description.trim() || null,
      videoFilename: file.name,
      videoContentType: file.type || 'video/mp4',
    }, file).subscribe({
      next: () => {
        this.uploading.set(false);
        this.file.set(null);
        this.form.reset({ captureDate: todayLocal(), zone: '', stageId: '', description: '', confirmCredit: false });
        this.load();
      },
      error: (error: unknown) => {
        this.uploading.set(false);
        this.formError.set(errorMessageOf(error,
          'No se pudo subir. Probá de nuevo en un rato.'));
        this.load();
      },
    });
  }

  protected discard(capture: CaptureProcessing): void {
    if (!window.confirm('¿Descartar este recorrido? Se borra el video subido.')) {
      return;
    }
    this.actionError.set(null);
    this.captures.discard(capture.captureId).subscribe({
      next: () => this.processing.update(list => list.filter(item => item.captureId !== capture.captureId)),
      error: (error: unknown) => this.actionError.set(errorMessageOf(error, 'No se pudo descartar el recorrido.')),
    });
  }

  /** Pasa una foto o video de sesión a otro lugar; sin lugar, queda en "General" y sale de los sectores. */
  protected moveMedia(asset: MediaAsset, sector: string | null): void {
    this.busyMedia.set(asset.id);
    this.actionError.set(null);
    this.progress.updateMediaSector(asset.id, sector).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: updated => {
        this.busyMedia.set(null);
        this.media.update(all => all.map(item => item.id === updated.id ? { ...item, sector: updated.sector } : item));
      },
      error: (error: unknown) => {
        this.busyMedia.set(null);
        this.actionError.set(errorMessageOf(error, 'No se pudo cambiar el lugar.'));
      },
    });
  }

  protected deleteMedia(asset: MediaAsset): void {
    if (!window.confirm('¿Borrar esta foto o video? El comprador deja de verlo.')) {
      return;
    }
    this.busyMedia.set(asset.id);
    this.actionError.set(null);
    this.progress.deleteMedia(asset.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busyMedia.set(null);
        this.media.update(all => all.filter(item => item.id !== asset.id));
      },
      error: (error: unknown) => {
        this.busyMedia.set(null);
        this.actionError.set(errorMessageOf(error, 'No se pudo borrar.'));
      },
    });
  }

  /** Un renombre lleva recorridos y fotos al nombre nuevo en el servidor: se vuelve a cargar todo. */
  protected sectorsSaved(): void {
    this.editingSectors.set(false);
    this.load();
  }

  protected saveCapture(capture: Capture3d, changes: CaptureUpdatePayload): void {
    this.actionError.set(null);
    this.captures.update(capture.id, changes).subscribe({
      next: updated => {
        this.available.update(list => list.map(item => (item.id === updated.id ? updated : item)));
      },
      error: (error: unknown) => this.actionError.set(errorMessageOf(error, 'No se pudieron guardar los datos del recorrido.')),
    });
  }

  private updateValidity(): void {
    const needsConfirmation = this.simulated() === false;
    this.formValid.set(this.form.controls.captureDate.valid
      && (!needsConfirmation || this.form.controls.confirmCredit.value));
  }

  /**
   * Consulta el avance mientras haya recorridos que todavía pueden cambiar. Cuando uno
   * desaparece de la lista es porque quedó disponible, y se recarga todo.
   */
  private watchProcessing(): void {
    this.polling?.unsubscribe();
    const active = () => this.processing().some(capture => !isDiscardable(capture));
    if (!active()) {
      return;
    }
    this.polling = timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
      .pipe(
        takeWhile((tick) => tick < MAX_POLLS && active()),
        switchMap(() => this.captures.inProcess(this.developmentId)),
        map(processing => processing.filter(capture => this.scope.owns(capture.unitId))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: processing => {
          const finished = processing.length < this.processing().length;
          this.processing.set(processing);
          if (finished) {
            this.load();
          }
        },
        error: () => {},
      });
  }
}
