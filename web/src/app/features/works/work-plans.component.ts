import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { FloorPlan, Unit } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { PlansService } from '../../core/api/plans.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { replaceQuery } from '../../core/routing/query-state';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { Point, depthFromImage, placePoint, polygonArea, svgPoints, toFlat, toPoints } from './plan-geometry';
import { WorkStore } from './work.store';

const MAX_PLAN_BYTES = 25 * 1024 * 1024;
const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

/** Ambiente en edición: se guardan todos juntos, con un solo botón. */
interface DraftRoom {
  name: string;
  points: Point[];
  declaredAreaM2: number | null;
}

/**
 * Editor de planos de una unidad, para el dueño.
 *
 * <p>Sigue lo que hace un desarrollista real: sube el plano que le entregó el estudio (PDF,
 * JPG o PNG, o una foto del plano impreso), dice cuánto mide la planta de ancho para dar
 * la escala, y marca encima los ambientes que el comprador va a poder tocar. Los ambientes
 * son opcionales: un plano sin ambientes marcados igual se muestra.
 */
@Component({
  selector: 'app-work-plans',
  imports: [DecimalPipe, FormsModule, RouterLink, LoadingStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page plans-editor">
      <header class="intro">
        <a class="back ov-hit" [routerLink]="backLink()">Ficha y galería</a>
        <h2>Planos{{ unit() ? ' de ' + unit()!.code : '' }}</h2>
        <p class="ov-lead">
          Subí el plano que te entregó el estudio (PDF, JPG o PNG) o una foto del plano impreso. Después marcá encima los
          ambientes: el comprador los toca para ver su superficie.
        </p>
      </header>

      @if (loading()) {
        <app-loading-state label="Cargando los planos…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else {
        <div class="floors">
          @for (plan of plans(); track plan.id) {
            <button type="button" class="floor" [class.active]="plan.id === selectedId()" (click)="select(plan)">
              {{ plan.name }}
            </button>
          }
          <form class="new-floor" (ngSubmit)="createPlan()">
            <label class="ov-visually-hidden" for="new-plan-name">Nombre de la planta nueva</label>
            <input id="new-plan-name" class="ov-input" name="newName" [(ngModel)]="newName" maxlength="80"
                   placeholder="Nueva planta, por ejemplo: Planta alta" />
            <button class="ov-btn ov-btn--secondary" type="submit" [disabled]="busy() || !newName.trim()">Agregar planta</button>
          </form>
        </div>

        @if (actionError(); as message) {
          <p class="ov-error" role="alert">{{ message }}</p>
        }

        @if (selected(); as plan) {
          <div class="workspace">
            <section class="ov-card side" aria-labelledby="file-title">
              <h3 id="file-title">1. El plano</h3>
              @if (plan.imageUrl) {
                <p class="muted ov-num">Imagen de {{ plan.imageWidthPx }} × {{ plan.imageHeightPx }} px.</p>
              } @else {
                <p class="muted">Todavía no tiene imagen. Sin imagen, la planta se muestra sólo con los ambientes marcados.</p>
              }
              <div class="row">
                <label class="ov-btn ov-btn--primary file">
                  {{ uploading() ? 'Procesando…' : (plan.imageUrl ? 'Reemplazar archivo' : 'Subir PDF o imagen') }}
                  <input type="file" accept="application/pdf,image/jpeg,image/png" (change)="upload(plan, $event)" [disabled]="busy()" />
                </label>
                @if (plan.imageUrl) {
                  <button class="ov-link" type="button" (click)="removeImage(plan)" [disabled]="busy()">Quitar imagen</button>
                }
              </div>
              <p class="muted">De un PDF se usa la primera página. Si el plano está en DWG, exportalo a PDF.</p>

              <h3>2. La escala</h3>
              <p class="muted">Cuánto mide la planta en la realidad. Con el ancho alcanza: la profundidad sale de la imagen.</p>
              <div class="pair">
                <div class="ov-field">
                  <label class="ov-eyebrow" for="plan-width">Ancho (m)</label>
                  <input id="plan-width" class="ov-input" type="number" min="0.5" max="500" step="0.01" name="width"
                         [(ngModel)]="widthM" (ngModelChange)="onWidthChange(plan)" />
                </div>
                <div class="ov-field">
                  <label class="ov-eyebrow" for="plan-depth">Profundidad (m)</label>
                  <input id="plan-depth" class="ov-input" type="number" min="0.5" max="500" step="0.01" name="depth"
                         [(ngModel)]="depthM" [readonly]="!!plan.imageWidthPx" />
                </div>
              </div>
              <div class="ov-field">
                <label class="ov-eyebrow" for="plan-name">Nombre de la planta</label>
                <input id="plan-name" class="ov-input" name="name" [(ngModel)]="planName" maxlength="80" />
              </div>
              <div class="row">
                <button class="ov-btn ov-btn--secondary" type="button" (click)="saveDimensions(plan)" [disabled]="busy() || !dimensionsValid()">
                  Guardar planta
                </button>
                <button class="ov-link danger" type="button" (click)="deletePlan(plan)" [disabled]="busy()">Borrar planta</button>
              </div>

              <h3>3. Los ambientes</h3>
              @if (drawing()) {
                <p class="hint" role="status">
                  Tocá el plano en cada esquina del ambiente. {{ draft().length }} {{ draft().length === 1 ? 'punto' : 'puntos' }}.
                </p>
                <div class="row">
                  <button class="ov-btn ov-btn--primary" type="button" (click)="closeDraft()" [disabled]="draft().length < 3">Cerrar ambiente</button>
                  <button class="ov-link" type="button" (click)="undoPoint()" [disabled]="draft().length === 0">Deshacer punto</button>
                  <button class="ov-link" type="button" (click)="cancelDraft()">Cancelar</button>
                </div>
              } @else if (naming()) {
                <form class="naming" (ngSubmit)="addRoom()">
                  <div class="ov-field">
                    <label class="ov-eyebrow" for="room-name">Nombre del ambiente</label>
                    <input id="room-name" class="ov-input" name="roomName" [(ngModel)]="roomName" maxlength="80" placeholder="Por ejemplo: Dormitorio principal" />
                  </div>
                  <div class="ov-field">
                    <label class="ov-eyebrow" for="room-area">Superficie del plano (m², opcional)</label>
                    <input id="room-area" class="ov-input" type="number" min="0.01" step="0.01" name="roomArea" [(ngModel)]="roomArea" />
                    <p class="muted">Si el plano dice la superficie, cargala: incluye muros y es la oficial. Si no, se usa la del dibujo ({{ draftArea() | number: '1.0-2' }} m²).</p>
                  </div>
                  <div class="row">
                    <button class="ov-btn ov-btn--primary" type="submit" [disabled]="!roomName.trim()">Agregar ambiente</button>
                    <button class="ov-link" type="button" (click)="cancelDraft()">Descartar</button>
                  </div>
                </form>
              } @else {
                <button class="ov-btn ov-btn--secondary" type="button" (click)="startDrawing()" [disabled]="busy()">Marcar un ambiente</button>
              }

              @if (rooms().length > 0) {
                <ul class="rooms">
                  @for (room of rooms(); track $index; let i = $index) {
                    <li class="room-row" [class.active]="i === highlighted()" (mouseenter)="highlighted.set(i)" (mouseleave)="highlighted.set(null)">
                      <span>{{ room.name }}</span>
                      <span class="muted ov-num">{{ areaOf(room) | number: '1.0-2' }} m²{{ room.declaredAreaM2 === null ? ' (dibujo)' : '' }}</span>
                      <button class="ov-link" type="button" (click)="removeRoom(i)" [attr.aria-label]="'Quitar ' + room.name">Quitar</button>
                    </li>
                  }
                </ul>
              }
              @if (roomsDirty()) {
                <div class="row">
                  <button class="ov-btn ov-btn--primary" type="button" (click)="saveRooms(plan)" [disabled]="busy()">Guardar ambientes</button>
                  <span class="unsaved">Cambios sin guardar</span>
                </div>
              }
              @if (saved(); as message) {
                <p class="saved" role="status">{{ message }}</p>
              }
            </section>

            <section class="ov-card canvas" aria-label="Plano">
              <svg #svg [attr.viewBox]="'0 0 ' + plan.widthM + ' ' + plan.depthM" [class.drawing]="drawing()"
                   (click)="onCanvasClick($event, svg, plan)" role="img" [attr.aria-label]="'Plano de ' + plan.name">
                @if (plan.imageUrl) {
                  <image [attr.href]="plan.imageUrl" x="0" y="0" [attr.width]="plan.widthM" [attr.height]="plan.depthM"
                         preserveAspectRatio="none" />
                } @else {
                  <rect class="blank" x="0" y="0" [attr.width]="plan.widthM" [attr.height]="plan.depthM" />
                }
                @for (room of rooms(); track $index; let i = $index) {
                  <polygon class="room" [class.active]="i === highlighted()" [attr.points]="svgPoints(room.points)"
                           vector-effect="non-scaling-stroke" />
                }
                @if (draft().length > 0) {
                  <polyline class="draft" [attr.points]="svgPoints(draft())" vector-effect="non-scaling-stroke" />
                  @for (point of draft(); track $index) {
                    <circle class="vertex" [attr.cx]="point.x" [attr.cy]="point.y" [attr.r]="vertexRadius()" />
                  }
                }
              </svg>
            </section>
          </div>
        } @else if (plans().length === 0) {
          <p class="muted">Agregá la primera planta con su nombre para empezar.</p>
        }
      }
    </div>
  `,
  styles: `
    .plans-editor {
      display: grid;
      gap: var(--ov-space-5);
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
      margin-top: var(--ov-space-3);
      font-size: var(--ov-text-md);
    }

    .muted,
    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .hint {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .floors {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2);
    }

    .floor {
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .floor.active {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .new-floor {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
      margin-left: auto;
    }

    .new-floor .ov-input {
      width: 18rem;
      max-width: 100%;
    }

    .workspace {
      display: grid;
      gap: var(--ov-space-5);
      align-items: start;
    }

    .side {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--ov-space-3);
    }

    .file {
      position: relative;
      overflow: hidden;
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

    .danger {
      color: var(--ov-danger);
    }

    .naming {
      display: grid;
      gap: var(--ov-space-3);
    }

    .rooms {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .room-row {
      display: grid;
      grid-template-columns: 1fr auto auto;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-2) 0;
      border-top: 1px solid var(--ov-line);
    }

    .room-row.active {
      color: var(--ov-accent);
    }

    .unsaved {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .saved {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
    }

    .canvas {
      padding: var(--ov-space-4);
      overflow: auto;
    }

    svg {
      display: block;
      width: 100%;
      height: auto;
      max-height: 75vh;
      background: var(--ov-surface);
    }

    svg.drawing {
      cursor: crosshair;
    }

    .blank {
      fill: var(--ov-surface-muted);
      stroke: var(--ov-line-strong);
      stroke-width: 1;
      vector-effect: non-scaling-stroke;
    }

    .room {
      fill: color-mix(in srgb, var(--ov-accent) 18%, transparent);
      stroke: var(--ov-accent);
      stroke-width: 2;
    }

    .room.active {
      fill: color-mix(in srgb, var(--ov-accent) 38%, transparent);
    }

    .draft {
      fill: color-mix(in srgb, var(--ov-status-delayed) 15%, transparent);
      stroke: var(--ov-status-delayed);
      stroke-width: 2;
      stroke-dasharray: 6 4;
    }

    .vertex {
      fill: var(--ov-status-delayed);
    }

    @media (min-width: 64rem) {
      .workspace {
        grid-template-columns: 22rem minmax(0, 1fr);
      }
    }
  `,
})
export class WorkPlansComponent implements OnInit, LeaveGuarded {

  protected readonly store = inject(WorkStore);
  private readonly route = inject(ActivatedRoute);
  private readonly plansService = inject(PlansService);
  private readonly developments = inject(DevelopmentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly svgPoints = svgPoints;

  protected readonly plans = signal<FloorPlan[]>([]);
  protected readonly unit = signal<Unit | null>(null);
  /** La planta elegida vive en la dirección (`?planta=`). */
  protected readonly selectedId = signal<string | null>(inject(ActivatedRoute).snapshot.queryParamMap.get('planta'));
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly saved = signal<string | null>(null);

  protected readonly rooms = signal<DraftRoom[]>([]);
  protected readonly roomsDirty = signal(false);
  protected readonly draft = signal<Point[]>([]);
  protected readonly drawing = signal(false);
  protected readonly naming = signal(false);
  protected readonly highlighted = signal<number | null>(null);

  protected newName = '';
  protected planName = '';
  protected widthM: number | null = null;
  protected depthM: number | null = null;
  protected roomName = '';
  protected roomArea: number | null = null;

  private readonly unitId = this.route.snapshot.paramMap.get('unitId') ?? '';
  private readonly router = inject(Router);

  protected readonly selected = computed(() => this.plans().find(plan => plan.id === this.selectedId()) ?? null);
  protected readonly draftArea = computed(() => polygonArea(this.draft()));

  /** Puntos visibles en cualquier escala: un poco menos del uno por ciento de la planta. */
  protected readonly vertexRadius = computed(() => {
    const plan = this.selected();
    return plan ? Math.max(plan.widthM, plan.depthM) / 120 : 0.1;
  });

  protected readonly backLink = computed(() => {
    const id = this.store.development()?.id ?? '';
    return ['/obras', id, 'casas', this.unitId, 'ficha'];
  });

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.roomsDirty() ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      plans: this.plansService.list(this.unitId),
      units: this.developments.units(this.store.development()?.id ?? ''),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ plans, units }) => {
          this.plans.set(plans);
          this.unit.set(units.find(unit => unit.id === this.unitId) ?? null);
          const first = plans.find(plan => plan.id === this.selectedId()) ?? plans[0];
          if (first) {
            this.select(first);
          }
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los planos.'));
        },
      });
  }

  protected select(plan: FloorPlan): void {
    if (this.roomsDirty() && plan.id !== this.selectedId()
      && !window.confirm('Hay ambientes sin guardar en esta planta. ¿Cambiar de planta igual?')) {
      return;
    }
    this.selectedId.set(plan.id);
    replaceQuery(this.router, this.route, { planta: plan.id });
    this.planName = plan.name;
    this.widthM = plan.widthM;
    this.depthM = plan.depthM;
    this.rooms.set(plan.rooms.map(room => ({
      name: room.name,
      points: toPoints(room.polygon),
      declaredAreaM2: room.declaredArea ? room.areaM2 : null,
    })));
    this.roomsDirty.set(false);
    this.cancelDraft();
    this.saved.set(null);
    this.actionError.set(null);
  }

  /** Una planta nueva arranca con una medida provisoria: se ajusta al subir el plano. */
  protected createPlan(): void {
    const name = this.newName.trim();
    if (!name) {
      return;
    }
    this.run(this.plansService.create(this.unitId, name, 10, 10), plan => {
      this.newName = '';
      this.plans.update(plans => [...plans, plan]);
      this.select(plan);
    }, 'No se pudo crear la planta.');
  }

  protected upload(plan: FloorPlan, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (!isPdf && !ACCEPTED_TYPES.includes(file.type)) {
      this.actionError.set('El plano tiene que ser PDF, JPG o PNG. Si lo tenés en DWG, exportalo a PDF.');
      return;
    }
    if (file.size > MAX_PLAN_BYTES) {
      this.actionError.set('El plano pesa más de 25 MB. Exportalo con menos resolución.');
      return;
    }
    this.uploading.set(true);
    this.run(this.plansService.uploadImage(plan.id, file), updated => {
      this.uploading.set(false);
      this.replace(updated);
      // La profundidad se ajusta a la imagen para que no se deforme.
      if (updated.imageWidthPx && updated.imageHeightPx && this.widthM) {
        this.depthM = depthFromImage(this.widthM, updated.imageWidthPx, updated.imageHeightPx);
      }
      this.saved.set('Plano subido. Revisá el ancho real y guardá la planta.');
    }, 'No se pudo subir el plano.', () => this.uploading.set(false));
  }

  protected removeImage(plan: FloorPlan): void {
    if (!window.confirm('¿Quitar la imagen del plano? Los ambientes marcados se conservan.')) {
      return;
    }
    this.run(this.plansService.removeImage(plan.id), updated => this.replace(updated), 'No se pudo quitar la imagen.');
  }

  protected onWidthChange(plan: FloorPlan): void {
    if (plan.imageWidthPx && plan.imageHeightPx && this.widthM) {
      this.depthM = depthFromImage(this.widthM, plan.imageWidthPx, plan.imageHeightPx);
    }
  }

  protected dimensionsValid(): boolean {
    const valid = (value: number | null) => value !== null && value >= 0.5 && value <= 500;
    return valid(this.widthM) && valid(this.depthM) && this.planName.trim().length > 0;
  }

  protected saveDimensions(plan: FloorPlan): void {
    this.run(this.plansService.update({
      id: plan.id, name: this.planName.trim(), widthM: this.widthM!, depthM: this.depthM!, orderIndex: plan.orderIndex,
    }), updated => {
      this.replace(updated);
      this.saved.set('Planta guardada.');
    }, 'No se pudo guardar la planta.');
  }

  protected deletePlan(plan: FloorPlan): void {
    if (!window.confirm(`¿Borrar la planta "${plan.name}" con sus ambientes? No se puede deshacer.`)) {
      return;
    }
    this.run(this.plansService.remove(plan.id), () => {
      const remaining = this.plans().filter(candidate => candidate.id !== plan.id);
      this.plans.set(remaining);
      this.roomsDirty.set(false);
      this.selectedId.set(null);
      if (remaining[0]) {
        this.select(remaining[0]);
      }
    }, 'No se pudo borrar la planta.');
  }

  protected startDrawing(): void {
    this.draft.set([]);
    this.drawing.set(true);
    this.naming.set(false);
    this.saved.set(null);
  }

  /** Convierte el clic a metros con la matriz del propio SVG: vale para cualquier zoom o tamaño de pantalla. */
  protected onCanvasClick(event: MouseEvent, svg: Element, plan: FloorPlan): void {
    if (!this.drawing()) {
      return;
    }
    const matrix = (svg as SVGSVGElement).getScreenCTM?.();
    if (!matrix) {
      return;
    }
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
    this.addPoint(placePoint({ x: point.x, y: point.y }, plan.widthM, plan.depthM));
  }

  addPoint(point: Point): void {
    this.draft.update(points => [...points, point]);
  }

  protected undoPoint(): void {
    this.draft.update(points => points.slice(0, -1));
  }

  protected closeDraft(): void {
    if (this.draft().length < 3) {
      return;
    }
    this.drawing.set(false);
    this.naming.set(true);
    this.roomName = '';
    this.roomArea = null;
  }

  protected cancelDraft(): void {
    this.draft.set([]);
    this.drawing.set(false);
    this.naming.set(false);
  }

  protected addRoom(): void {
    const name = this.roomName.trim();
    if (!name || this.draft().length < 3) {
      return;
    }
    const declared = this.roomArea !== null && this.roomArea > 0 ? this.roomArea : null;
    this.rooms.update(rooms => [...rooms, { name, points: this.draft(), declaredAreaM2: declared }]);
    this.roomsDirty.set(true);
    this.cancelDraft();
  }

  protected removeRoom(index: number): void {
    this.rooms.update(rooms => rooms.filter((_, i) => i !== index));
    this.roomsDirty.set(true);
    this.highlighted.set(null);
  }

  protected areaOf(room: DraftRoom): number {
    return room.declaredAreaM2 ?? polygonArea(room.points);
  }

  protected saveRooms(plan: FloorPlan): void {
    const payload = this.rooms().map(room => ({ name: room.name, polygon: toFlat(room.points), declaredAreaM2: room.declaredAreaM2 }));
    this.run(this.plansService.replaceRooms(plan.id, payload), updated => {
      this.roomsDirty.set(false);
      this.replace(updated);
      this.saved.set('Ambientes guardados. Ya los ve el comprador.');
    }, 'No se pudieron guardar los ambientes.');
  }

  private replace(updated: FloorPlan): void {
    this.plans.update(plans => plans.map(plan => (plan.id === updated.id ? updated : plan)));
  }

  private run<T>(request: Observable<T>, done: (value: T) => void, fallback: string, onError?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.saved.set(null);
    request.subscribe({
      next: value => {
        this.busy.set(false);
        done(value);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        onError?.();
        this.actionError.set(errorMessageOf(error, fallback));
      },
    });
  }
}
