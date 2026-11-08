import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, catchError, forkJoin, last, map, of, switchMap, takeWhile, tap, timer } from 'rxjs';
import { AssignedHouse, Capture3d, CaptureProcessing, MediaAsset, Stage } from '../../core/api/api.models';
import { CapturesService } from '../../core/api/captures.service';
import { DevelopmentsService } from '../../core/api/developments.service';
import { ProgressService } from '../../core/api/progress.service';
import { StagesService } from '../../core/api/stages.service';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, uploadCutWarning, warnOnUnload } from '../../shared/ui/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { FieldHeaderComponent } from './field-header.component';
import { FieldHouseStore } from './field-house.store';
import { FieldSessionSummaryComponent } from './field-session-summary.component';
import { FieldUploadListComponent } from './field-upload-list.component';
import { todayLocal } from '../../shared/time/local-date';
import {
  SectorStatus, SessionPlace, nextPlace, placeHasMaterial, reconstructionMinutes, sessionDayLine, sessionPlaceLine,
  sessionPlaceProgress, sessionPlaces, sessionStatus, workingStage,
} from './field-derivations';

const POLL_INTERVAL_MS = 10_000;
const MAX_POLLS = 180;
const MAX_VIDEO_BYTES = 2 * 1024 * 1024 * 1024;
/** El mismo máximo que valida el servidor al confirmar. */
const MAX_SPZ_BYTES = 50 * 1024 * 1024;

/** El estado de un sector en una línea; si falló, con el motivo que da el backend. */
export function sectorStateText(status: SectorStatus): string {
  switch (status.state) {
    case 'DONE': return 'Grabado';
    case 'PROCESSING': return 'Reconstruyendo…';
    case 'FAILED': return `Falló: ${status.failureReason || 'no se pudo procesar'}`;
    case 'MISSING': return 'Falta grabar';
  }
}

/** Lo que el teléfono deja elegir como SPZ: el servidor vuelve a medirlo y a leer su encabezado. */
export function spzProblem(file: Pick<File, 'name' | 'size'>): string | null {
  if (!/\.spz$/i.test(file.name)) {
    return 'El archivo tiene que ser un .spz. En Scaniverse: Compartir → Exportar → SPZ.';
  }
  if (file.size > MAX_SPZ_BYTES) {
    return `El archivo pesa ${Math.ceil(file.size / (1024 * 1024))} MB y el máximo es 50 MB.`;
  }
  return file.size === 0 ? 'El archivo está vacío.' : null;
}

/** Hasta cuánto pesa un archivo de la sesión: fotos como en un avance, videos cortos de un lugar. */
const MAX_SESSION_PHOTO_BYTES = 15 * 1024 * 1024;
const MAX_SESSION_VIDEO_BYTES = 500 * 1024 * 1024;
const MAX_SESSION_FILES = 10;

/** Si un archivo puede subirse como foto o video de un lugar. */
export function sessionMediaProblem(file: Pick<File, 'type' | 'size'>): string | null {
  if (file.type.startsWith('image/')) {
    return file.size > MAX_SESSION_PHOTO_BYTES ? 'pesa más de 15 MB.' : null;
  }
  if (file.type.startsWith('video/')) {
    return file.size > MAX_SESSION_VIDEO_BYTES ? 'el video pesa más de 500 MB: filmá uno más corto.' : null;
  }
  return 'no es una foto ni un video.';
}

/** Una foto o video de un lugar subiéndose, o cortado, con la fecha de la sesión en que se eligió. */
interface SessionMediaUpload {
  key: number;
  sector: string;
  file: File;
  date: string;
  percent: number;
  state: 'uploading' | 'failed';
  error: string | null;
}

/**
 * Una subida en curso o cortada. Guarda el archivo y lo que el servidor ya creó, así un
 * reintento sigue desde donde se cortó sin volver a elegir nada.
 */
interface SectorUpload {
  sector: string;
  kind: 'VIDEO' | 'SPZ';
  file: File;
  date: string;
  contentType: string;
  percent: number;
  captureId: string | null;
  uploadUrl: string | null;
  uploaded: boolean;
  failed: string | null;
}

/**
 * El recorrido 3D de una fecha, sector por sector:
 * un anillo con cuántos lugares tienen material y una tarjeta por lugar, con el frente marcado, lo
 * que tiene y sus dos acciones. El próximo por recorrer lleva el borde negro; uno con material se
 * pliega y se abre al tocarlo. El responsable define qué sectores se
 * graban en la casa; acá el encargado elige la fecha y, por cada sector, sube un video (que
 * reconstruye el servicio externo) o el SPZ que exportó de Scaniverse (sin costo, disponible
 * apenas termina). Con todos los sectores de la fecha, el comprador viaja en el tiempo en
 * cada lugar de su casa.
 *
 * <p>Con la reconstrucción real cada video consume un crédito: se pide confirmarlo una vez
 * antes de subir, igual que en la pantalla de recorridos. Un SPZ no lo necesita.
 */
@Component({
  selector: 'app-field-capture',
  imports: [FormsModule, NgTemplateOutlet, RouterLink, MatIconModule, LoadingStateComponent, ErrorStateComponent,
    FieldHeaderComponent, FieldSessionSummaryComponent, FieldUploadListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      @if (loading()) {
        <app-loading-state label="Cargando la casa…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (house(); as current) {
        <app-field-header [title]="date() === today ? 'Recorrido de hoy' : 'Recorrido del ' + dayLine().replace(dayPrefix, '')"
                          [subtitle]="current.code + ' · ' + dayLine()">
          @if (current.sectors.length > 0) {
            <button type="button" class="other-date" [attr.aria-expanded]="choosingDate()" (click)="choosingDate.set(!choosingDate())">
              Otra fecha
            </button>
          }
        </app-field-header>

        @if (current.sectors.length === 0) {
          <p class="notice">
            El responsable todavía no definió qué sectores se graban en esta casa. Pedile que los cargue en la pestaña
            Recorridos 3D de la casa.
          </p>
        } @else {
          @if (choosingDate()) {
            <div class="ov-field">
              <label class="label" for="capture-date">Fecha de la sesión</label>
              <input id="capture-date" class="ov-input" type="date" name="date" [max]="today"
                     [ngModel]="date()" (ngModelChange)="date.set($event || today)" />
            </div>
          }

          <app-field-session-summary [done]="placeProgress().done" [total]="placeProgress().total" />

          @if (published(); as done) {
            <p class="published" role="status">
              {{ done.sector }} ya está en el recorrido.
              <a [routerLink]="['/mi-propiedad', current.unitId, 'recorrido']"
                 [queryParams]="{ obra: current.developmentId, desde: 'hoy', mes: done.month, sector: done.sector }">Ver cómo lo ve el comprador</a>
            </p>
          }

          @if (simulated() === false) {
            <label class="check">
              <input type="checkbox" [ngModel]="creditConfirmed()" (ngModelChange)="creditConfirmed.set($event)" />
              <span>Entiendo que cada video consume un crédito del servicio de reconstrucción y que son las tomas definitivas.</span>
            </label>
          }

          <ol class="sectors">
            @for (status of places(); track status.sector) {
              @let done = hasMaterial(status);
              @let open = !done || expanded() === status.sector;
              <li class="sector" [attr.data-state]="status.state" [class.has-media]="done" [class.next]="status.sector === next()">
                <ng-template #head>
                  <span class="mark" aria-hidden="true">
                    @if (done) {
                      <mat-icon svgIcon="check" />
                    }
                  </span>
                  <span class="sector-body">
                    <span class="name-line">
                      <span class="sector-name">{{ status.sector }}</span>
                      @if (status.sector === front() && status.sector.toLowerCase() !== 'frente') {
                        <span class="front">Frente</span>
                      }
                    </span>
                    @if (!uploadFor(status.sector)) {
                      <span class="sector-state">{{ placeLine(status) }}</span>
                    }
                  </span>
                </ng-template>
                @if (done) {
                  <button type="button" class="head" [attr.aria-expanded]="open" (click)="toggleExpanded(status.sector)">
                    <ng-container [ngTemplateOutlet]="head" />
                  </button>
                } @else {
                  <div class="head"><ng-container [ngTemplateOutlet]="head" /></div>
                }

                @if (uploadFor(status.sector); as current) {
                  <div class="upload">
                    <p class="sector-state">
                      {{ current.kind === 'VIDEO' ? 'Subiendo video' : 'Subiendo archivo de Scaniverse' }} · <span class="ov-num">{{ current.percent }}%</span>
                    </p>
                    <div class="ov-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100"
                         [attr.aria-valuenow]="current.percent" [attr.aria-label]="'Subida de ' + status.sector">
                      <span [style.width.%]="current.percent"></span>
                    </div>
                    @if (current.failed; as message) {
                      <p class="problem" role="alert">{{ message }}</p>
                      <div class="retry">
                        <button type="button" class="ov-btn ov-btn--secondary" (click)="retry()">Reintentar</button>
                        <button type="button" class="ov-link" (click)="cancelUpload()">Cancelar</button>
                      </div>
                    }
                  </div>
                } @else if (open) {
                  <div class="choices">
                    @if (status.state === 'PROCESSING') {
                      <span class="action rebuilding"><mat-icon svgIcon="view_in_ar" aria-hidden="true" />Reconstruyendo</span>
                    } @else if (status.state === 'MISSING' || status.state === 'FAILED') {
                      <button type="button" class="action primary" [attr.aria-expanded]="choosing3d() === status.sector"
                              (click)="toggle3d(status.sector)"><mat-icon svgIcon="view_in_ar" aria-hidden="true" />Recorrido 3D</button>
                    }
                    <label class="action">
                      <mat-icon svgIcon="photo_camera" aria-hidden="true" />
                      {{ done ? 'Agregar fotos' : 'Fotos o video' }}
                      <input type="file" accept="image/*,video/*" multiple (change)="pickMedia(status.sector, $event)"
                             [attr.aria-label]="'Subir fotos o video de ' + status.sector" />
                    </label>
                  </div>
                  @if (choosing3d() === status.sector && (status.state === 'MISSING' || status.state === 'FAILED')) {
                    <div class="choices three-d">
                      <label class="action" [class.disabled]="!canUploadVideo()">
                        Subir video
                        <input type="file" accept="video/*" [disabled]="!canUploadVideo()"
                               (change)="pickVideo(status.sector, $event)" [attr.aria-label]="'Subir el video de ' + status.sector" />
                      </label>
                      <div class="spz">
                        <!-- Sin accept: el teléfono no conoce el tipo .spz y lo mostraría deshabilitado. Se valida al elegir. -->
                        <label class="action" [class.disabled]="upload() !== null">
                          Subir archivo de Scaniverse
                          <input type="file" [disabled]="upload() !== null" (change)="pickSpz(status.sector, $event)"
                                 [attr.aria-label]="'Subir el archivo de Scaniverse de ' + status.sector" />
                        </label>
                        <p class="hint">En Scaniverse: Compartir → Exportar → SPZ</p>
                      </div>
                    </div>
                  }
                }
                @if (mediaUploadsFor(status.sector).length > 0) {
                  <app-field-upload-list [items]="mediaUploadsFor(status.sector)"
                                         (retry)="retryMedia($event)" (remove)="removeMedia($event)" />
                }
              </li>
            }
          </ol>

          @if (error(); as message) {
            <p class="problem" role="alert">{{ message }}</p>
          }
          @if (upload() && !upload()!.failed) {
            <p class="hint">No cierres la pantalla hasta que termine la subida.</p>
          }

          <details class="ov-disclosure guide">
            <summary>Cómo filmar para que el 3D salga bien</summary>
            <ul>
              <li>Parate en el mismo punto de la vez anterior: así las fechas se comparan.</li>
              <li>Caminá despacio uno a tres minutos, sin cortes. El máximo es tres minutos.</li>
              <li>Rodeá el lugar desde distintas alturas; evitá zonas muy oscuras y gente cruzando.</li>
              <li>Un sector grande, como una planta entera, sale con menos detalle que un ambiente.</li>
            </ul>
          </details>
        }
      }
    </div>
  `,
  styles: `
    .field {
      display: grid;
      gap: var(--ov-space-4);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-3) var(--ov-gutter) var(--ov-space-8);
    }

    .hint,
    .sector-state {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .other-date {
      min-height: 3rem;
      padding: 0 var(--ov-space-2);
      border: 0;
      background: none;
      color: var(--ov-accent);
      font: inherit;
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .label {
      font-weight: var(--ov-weight-semibold);
    }

    .notice,
    .sector {
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .check {
      display: flex;
      align-items: flex-start;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-status-delayed);
      border-radius: var(--ov-radius-md);
      background: var(--ov-status-delayed-soft);
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .check input {
      width: 1.25rem;
      height: 1.25rem;
      accent-color: var(--ov-accent);
    }

    .sectors {
      display: grid;
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .sector,
    .upload,
    .spz {
      display: grid;
      gap: var(--ov-space-3);
    }

    /* El próximo lugar por recorrer, marcado como en el boceto. */
    .sector.next {
      border: 2px solid var(--ov-ink);
    }

    .head {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      width: 100%;
      min-height: 3rem;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: left;
    }

    button.head {
      cursor: pointer;
    }

    .sector-body {
      display: grid;
      flex: 1;
      min-width: 0;
    }

    .name-line {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      font-weight: var(--ov-weight-semibold);
    }

    .front {
      padding: 0 var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
    }

    .mark {
      display: grid;
      flex: 0 0 1.75rem;
      place-items: center;
      height: 1.75rem;
      border: 2px solid var(--ov-line-strong);
      border-radius: 50%;
      color: var(--ov-on-media);
    }

    .mark mat-icon {
      width: 1rem;
      height: 1rem;
    }

    .has-media .mark {
      border-color: var(--ov-accent);
      background: var(--ov-accent);
    }

    .sector[data-state='PROCESSING'] .mark {
      border-color: var(--ov-status-delayed);
      background: var(--ov-status-delayed-soft);
    }

    .has-media .sector-state {
      color: var(--ov-accent);
    }

    .sector[data-state='PROCESSING'] .sector-state,
    .sector[data-state='FAILED'] .sector-state {
      color: var(--ov-status-delayed);
    }

    .choices {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--ov-space-2);
    }

    .action {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--ov-space-2);
      overflow: hidden;
      min-height: 3rem;
      padding: 0 var(--ov-space-3);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      text-align: center;
      cursor: pointer;
    }

    .action.primary {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-surface);
      font-weight: var(--ov-weight-semibold);
    }

    .action.rebuilding {
      border-color: var(--ov-status-delayed);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      cursor: default;
    }

    .action.disabled {
      opacity: 0.45;
    }

    .action input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .action:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .three-d {
      padding: var(--ov-space-3);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .spz {
      gap: var(--ov-space-1);
    }

    .spz .hint {
      font-size: var(--ov-text-xs);
    }

    .retry {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .ov-link {
      min-height: 3rem;
    }

    .problem {
      font-size: var(--ov-text-sm);
    }

    .problem::before {
      content: '⚠ ';
      color: var(--ov-status-delayed);
    }

    .published {
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
      font-size: var(--ov-text-sm);
    }

    .published a {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .guide {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .guide summary {
      min-height: 3rem;
      align-content: center;
      cursor: pointer;
    }

    .guide ul {
      display: grid;
      gap: var(--ov-space-2);
      margin: var(--ov-space-3) 0 0;
      padding-left: var(--ov-space-5);
    }
  `,
})
export class FieldCaptureComponent implements OnInit, LeaveGuarded {

  private readonly route = inject(ActivatedRoute);
  private readonly developments = inject(DevelopmentsService);
  private readonly capturesService = inject(CapturesService);
  private readonly stagesService = inject(StagesService);
  private readonly units = inject(UnitsService);
  private readonly progressService = inject(ProgressService);
  private readonly houseStore = inject(FieldHouseStore);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  /** Salir corta las subidas en curso: la del recorrido 3D y las de fotos o videos de un lugar. */
  leaveWarning(): string | null {
    const upload = this.upload();
    if (upload && !upload.uploaded && !upload.failed) {
      return uploadCutWarning(upload.kind === 'VIDEO' ? 'el video' : 'el recorrido 3D');
    }
    const media = this.mediaUploads().filter(item => item.state === 'uploading').length;
    return media > 0 ? uploadCutWarning(media === 1 ? '1 foto o video' : media + ' fotos o videos') : null;
  }

  protected readonly today = todayLocal();
  private readonly unitId = this.route.snapshot.paramMap.get('unitId') ?? '';

  protected readonly house = signal<AssignedHouse | null>(null);
  private readonly stage = signal<Stage | null>(null);
  private readonly available = signal<Capture3d[]>([]);
  private readonly processing = signal<CaptureProcessing[]>([]);
  protected readonly simulated = signal<boolean | null>(null);
  protected readonly creditConfirmed = signal(false);
  protected readonly date = signal(todayLocal());
  protected readonly dayLine = computed(() => sessionDayLine(this.date()));
  /** El día de la semana, que en el título sobra: ya está en la línea de abajo. */
  protected readonly dayPrefix = /^\S+ /;
  protected readonly choosingDate = signal(false);
  /** El lugar marcado como frente; nulo si no hay o no se pudo leer (la marca es un adorno). */
  protected readonly front = signal<string | null>(null);
  protected readonly expanded = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly upload = signal<SectorUpload | null>(null);
  protected readonly published = signal<{ sector: string; month: string } | null>(null);
  private polling: Subscription | null = null;
  private transfer: Subscription | null = null;

  protected readonly statuses = computed(() =>
    sessionStatus(this.house()?.sectors ?? [], this.available(), this.processing(), this.unitId, this.date()));
  protected readonly media = signal<MediaAsset[]>([]);
  protected readonly mediaUploads = signal<SessionMediaUpload[]>([]);
  protected readonly choosing3d = signal<string | null>(null);
  private nextMediaKey = 0;
  private readonly mediaTransfers = new Map<number, Subscription>();

  protected readonly places = computed(() => sessionPlaces(
    this.statuses(), this.media(), this.available(), this.processing(), this.unitId, this.date()));
  protected readonly placeProgress = computed(() => sessionPlaceProgress(this.places()));
  protected readonly next = computed(() => nextPlace(this.places()));
  protected readonly hasMaterial = placeHasMaterial;

  protected readonly canUploadVideo = computed(() =>
    this.upload() === null && this.simulated() !== null && (this.simulated() || this.creditConfirmed()));
  protected readonly stateText = sectorStateText;

  ngOnInit(): void {
    this.capturesService.reconstructionMode().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: mode => this.simulated.set(mode.simulated),
      // Sin saber el modo no se ofrece subir: más vale no enviar que gastar un crédito sin aviso.
      error: () => this.error.set('No se pudo verificar el modo de reconstrucción. Recargá la pantalla para grabar.'),
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.developments.myWork().pipe(
      switchMap(houses => {
        const house = houses.find(candidate => candidate.unitId === this.unitId);
        if (!house) {
          throw new Error('No estás asignado a esta casa.');
        }
        this.house.set(house);
        return forkJoin({
          available: this.capturesService.listByDevelopment(house.developmentId),
          processing: this.capturesService.inProcess(house.developmentId),
          stages: this.stagesService.listByUnit(house.unitId),
          media: this.progressService.media(house.developmentId),
          sectors: this.units.sectorDetail(house.unitId).pipe(catchError(() => of([]))),
        });
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: ({ available, processing, stages, media, sectors }) => {
        this.front.set(sectors.find(sector => sector.front)?.name ?? null);
        this.available.set(available);
        this.processing.set(processing);
        this.media.set(media);
        this.stage.set(workingStage(stages));
        this.loading.set(false);
        this.watch();
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(error instanceof Error ? error.message : errorMessageOf(error, 'No se pudo cargar la casa.'));
      },
    });
  }

  protected placeLine(place: SessionPlace): string {
    return sessionPlaceLine(place, reconstructionMinutes(this.processing(), this.unitId, this.date(), place.sector));
  }

  protected toggleExpanded(sector: string): void {
    this.expanded.set(this.expanded() === sector ? null : sector);
  }

  protected toggle3d(sector: string): void {
    this.choosing3d.set(this.choosing3d() === sector ? null : sector);
  }

  protected mediaUploadsFor(sector: string): SessionMediaUpload[] {
    return this.mediaUploads().filter(item => item.sector === sector);
  }

  /** Fotos y videos del lugar, con la fecha de la sesión: suben y se publican de a uno, con su progreso. */
  protected pickMedia(sector: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    const problems: string[] = [];
    const accepted = files.filter(file => {
      const problem = sessionMediaProblem(file);
      if (problem) {
        problems.push(`${file.name}: ${problem}`);
      }
      return !problem;
    }).slice(0, MAX_SESSION_FILES);
    if (files.length - problems.length > MAX_SESSION_FILES) {
      problems.push(`Se suben hasta ${MAX_SESSION_FILES} archivos por vez.`);
    }
    this.error.set(problems.length > 0 ? problems.join(' ') : null);
    const added = accepted.map(file => ({
      key: this.nextMediaKey++, sector, file, date: this.date(), percent: 0, state: 'uploading' as const, error: null,
    }));
    this.mediaUploads.update(items => [...items, ...added]);
    added.forEach(item => this.uploadMedia(item.key));
  }

  protected retryMedia(key: number): void {
    this.patchMedia(key, { state: 'uploading', percent: 0, error: null });
    this.uploadMedia(key);
  }

  protected removeMedia(key: number): void {
    this.mediaTransfers.get(key)?.unsubscribe();
    this.mediaTransfers.delete(key);
    this.mediaUploads.update(items => items.filter(item => item.key !== key));
  }

  private uploadMedia(key: number): void {
    const house = this.house();
    const item = this.mediaUploads().find(candidate => candidate.key === key);
    if (!house || !item) {
      return;
    }
    this.mediaTransfers.set(key, this.progressService.uploadSessionMedia(
      house.developmentId, { unitId: house.unitId, sector: item.sector, takenOn: item.date }, item.file,
    ).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: event => {
        if (event.kind === 'progress') {
          this.patchMedia(key, { percent: event.percent });
          return;
        }
        // Subida y publicada: sale de la lista y el lugar la cuenta.
        this.mediaTransfers.delete(key);
        this.mediaUploads.update(items => items.filter(candidate => candidate.key !== key));
        this.refreshMedia();
        this.houseStore.invalidate();
      },
      error: (error: unknown) => this.patchMedia(key, {
        state: 'failed',
        error: error instanceof HttpErrorResponse && error.status === 0
          ? 'Se cortó la conexión.'
          : errorMessageOf(error, 'No subió.'),
      }),
    }));
  }

  private patchMedia(key: number, changes: Partial<SessionMediaUpload>): void {
    this.mediaUploads.update(items => items.map(item => item.key === key ? { ...item, ...changes } : item));
  }

  private refreshMedia(): void {
    const house = this.house();
    if (!house) {
      return;
    }
    this.progressService.media(house.developmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: media => this.media.set(media),
      error: () => {},
    });
  }

  protected uploadFor(sector: string): SectorUpload | null {
    const current = this.upload();
    return current?.sector === sector ? current : null;
  }

  protected pickVideo(sector: string, event: Event): void {
    const file = this.takeFile(event);
    if (!file || !this.canUploadVideo()) {
      return;
    }
    if (!file.type.startsWith('video/')) {
      this.error.set('El archivo tiene que ser un video.');
      return;
    }
    if (file.size > MAX_VIDEO_BYTES) {
      this.error.set('El video pesa más de 2 GB. Filmá un recorrido más corto, de uno a tres minutos.');
      return;
    }
    this.start(sector, 'VIDEO', file, file.type || 'video/mp4');
  }

  protected pickSpz(sector: string, event: Event): void {
    const file = this.takeFile(event);
    if (!file || this.upload() !== null) {
      return;
    }
    const problem = spzProblem(file);
    if (problem) {
      this.error.set(problem);
      return;
    }
    this.start(sector, 'SPZ', file, 'application/octet-stream');
  }

  /** Sigue desde donde se cortó: si la captura ya existe no la vuelve a crear, y si el archivo ya subió no lo vuelve a subir. */
  protected retry(): void {
    this.patchUpload({ failed: null });
    this.run();
  }

  /** Abandona la subida. Lo que el servidor haya creado queda pendiente y no traba el sector. */
  protected cancelUpload(): void {
    this.transfer?.unsubscribe();
    this.upload.set(null);
    this.refresh();
  }

  private takeFile(event: Event): File | null {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    return file;
  }

  private start(sector: string, kind: SectorUpload['kind'], file: File, contentType: string): void {
    this.error.set(null);
    this.published.set(null);
    this.upload.set({
      sector, kind, file, date: this.date(), contentType,
      percent: 0, captureId: null, uploadUrl: null, uploaded: false, failed: null,
    });
    this.run();
  }

  /** Alta, subida con progreso y confirmación. Cada paso deja anotado lo que logró. */
  private run(): void {
    const current = this.upload();
    const house = this.house();
    if (!current || !house) {
      return;
    }
    const stageId = this.stage()?.id ?? null;
    const created: Observable<{ captureId: string; uploadUrl: string; contentType: string }> = current.captureId
      ? of({ captureId: current.captureId, uploadUrl: current.uploadUrl!, contentType: current.contentType })
      : current.kind === 'SPZ'
        ? this.capturesService.createSpz(house.developmentId, {
          captureDate: current.date, description: null, unitId: house.unitId, stageId,
          zone: current.sector, filename: current.file.name, fileSize: current.file.size,
        })
        : this.capturesService.createFromVideo(house.developmentId, {
          captureDate: current.date, description: null, unitId: house.unitId, stageId,
          zone: current.sector, videoFilename: current.file.name, videoContentType: current.contentType,
        }).pipe(map(job => ({ captureId: job.captureId, uploadUrl: job.uploadUrl!, contentType: job.contentType ?? current.contentType })));

    this.transfer?.unsubscribe();
    this.transfer = created.pipe(
      tap(capture => this.patchUpload({ captureId: capture.captureId, uploadUrl: capture.uploadUrl, contentType: capture.contentType })),
      switchMap(capture => this.upload()?.uploaded
        ? of(capture)
        : this.capturesService.putWithProgress(capture.uploadUrl, current.file, capture.contentType).pipe(
          tap(percent => this.patchUpload({ percent })),
          last(),
          tap(() => this.patchUpload({ uploaded: true, percent: 100 })),
          map(() => capture))),
      switchMap(capture => current.kind === 'SPZ'
        ? this.capturesService.confirmSpz(capture.captureId).pipe(map(() => true))
        : this.capturesService.confirmVideo(capture.captureId).pipe(map(() => true))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: () => {
        this.upload.set(null);
        if (current.kind === 'SPZ') {
          this.published.set({ sector: current.sector, month: current.date.slice(0, 7) });
        }
        this.refresh();
        this.houseStore.invalidate();
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  /**
   * Un corte de red o una falla del servidor se reintenta con lo ya hecho. Un rechazo (un
   * archivo que no es SPZ, un sector ocupado ese día) no: el servidor ya descartó lo que
   * había creado, y se dice por qué.
   */
  private fail(error: unknown): void {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    if (status === 0 || status >= 500 || status === 408 || status === 429) {
      this.patchUpload({
        failed: status === 0
          ? 'Se cortó la conexión. Reintentá: no hace falta volver a elegir el archivo.'
          : errorMessageOf(error, 'El servidor no respondió. Reintentá en un momento.'),
      });
      return;
    }
    this.upload.set(null);
    this.error.set(errorMessageOf(error, 'No se pudo subir. Si sigue pasando, avisale al responsable.'));
    this.refresh();
  }

  private patchUpload(changes: Partial<SectorUpload>): void {
    this.upload.update(current => current ? { ...current, ...changes } : current);
  }

  private refresh(): void {
    const house = this.house();
    if (!house) {
      return;
    }
    forkJoin({
      available: this.capturesService.listByDevelopment(house.developmentId),
      processing: this.capturesService.inProcess(house.developmentId),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ available, processing }) => {
        this.available.set(available);
        this.processing.set(processing);
        this.watch();
      },
      error: () => {},
    });
  }

  /** Mientras haya videos reconstruyéndose, se consulta cada diez segundos hasta que terminen. */
  private watch(): void {
    this.polling?.unsubscribe();
    const active = () => this.statuses().some(status => status.state === 'PROCESSING');
    if (!active()) {
      return;
    }
    const house = this.house();
    if (!house) {
      return;
    }
    this.polling = timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS).pipe(
      takeWhile(tick => tick < MAX_POLLS && active()),
      switchMap(() => forkJoin({
        available: this.capturesService.listByDevelopment(house.developmentId),
        processing: this.capturesService.inProcess(house.developmentId),
      })),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: ({ available, processing }) => {
        this.available.set(available);
        this.processing.set(processing);
      },
      error: () => {},
    });
  }
}
