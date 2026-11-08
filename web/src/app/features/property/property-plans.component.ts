import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { PublicRoom } from '../../core/api/api.models';
import { EmptyStateComponent } from '../../shared/ui/page-states.component';
import { PropertyViewStore } from './property-view.store';

const ZOOM_STEPS = [0.75, 1, 1.25, 1.5, 2];

/** Metros enteros de 0 al límite, para la grilla. */
function metersUpTo(limit: number): number[] {
  return Array.from({ length: Math.floor(limit) + 1 }, (_, meter) => meter);
}

/**
 * Planos a escala real. El SVG usa metros como unidad, así el dibujo respeta las
 * proporciones del plano y cualquier pantalla lo ajusta a su ancho.
 *
 * <p>Cada ambiente es un control: se elige con un toque, un clic o el teclado, y muestra
 * su superficie. Si el dueño declaró la del plano de arquitectura, se aclara.
 */
@Component({
  selector: 'app-property-plans',
  imports: [DecimalPipe, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.view(); as view) {
      <div class="ov-page plans">
        <header class="intro">
          <h2 class="ov-display">Los planos</h2>
          <p class="ov-lead">Tocá un ambiente para conocer su superficie.</p>
        </header>

        @if (view.plans.length === 0) {
          <app-empty-state title="Todavía no hay planos cargados" />
        } @else {
          <div class="toolbar">
            <div class="floors" role="tablist" aria-label="Plantas">
              @for (plan of view.plans; track $index; let i = $index) {
                <button type="button" role="tab" class="floor ov-hit" [class.active]="i === planIndex()"
                        [attr.aria-selected]="i === planIndex()" (click)="selectPlan(i)">{{ plan.name }}</button>
              }
            </div>
            <div class="zoom" aria-label="Zoom">
              <button type="button" class="round ov-hit" (click)="zoom(-1)" [disabled]="zoomIndex() === 0"
                      aria-label="Alejar">−</button>
              <span class="ov-num level" aria-live="polite">{{ zoomLevel() * 100 | number: '1.0-0' }}%</span>
              <button type="button" class="round ov-hit" (click)="zoom(1)" [disabled]="zoomIndex() === zoomSteps.length - 1"
                      aria-label="Acercar">+</button>
            </div>
          </div>

          @if (plan(); as current) {
            <div class="ov-card canvas">
              <div class="scroller">
                <svg
                  [class.with-image]="!!current.imageUrl"
                  [attr.viewBox]="'-0.5 -0.5 ' + (current.widthM + 1) + ' ' + (current.depthM + 1)"
                  [style.width.%]="zoomLevel() * 100"
                  role="group"
                  [attr.aria-label]="'Plano de ' + current.name"
                >
                  @if (current.imageUrl) {
                    <!-- El plano del estudio, estirado sobre la planta: los ambientes van encima, en metros. -->
                    <image [attr.href]="current.imageUrl" x="0" y="0" [attr.width]="current.widthM"
                           [attr.height]="current.depthM" preserveAspectRatio="none" />
                  } @else {
                    <!-- Grilla de un metro. Líneas y no un <pattern>: con base href, url(#id)
                         no resuelve en todos los navegadores. -->
                    @for (x of gridX(); track x) {
                      <line class="grid-line" [attr.x1]="x" y1="0" [attr.x2]="x" [attr.y2]="current.depthM" />
                    }
                    @for (y of gridY(); track y) {
                      <line class="grid-line" x1="0" [attr.y1]="y" [attr.x2]="current.widthM" [attr.y2]="y" />
                    }
                  }

                  @for (room of current.rooms; track $index; let r = $index) {
                    <g
                      class="room"
                      [class.selected]="r === roomIndex()"
                      tabindex="0"
                      role="button"
                      [attr.aria-pressed]="r === roomIndex()"
                      [attr.aria-label]="room.name + ', ' + (room.areaM2 | number: '1.0-2') + ' metros cuadrados'"
                      (click)="selectRoom(r)"
                      (keydown.enter)="selectRoom(r)"
                      (keydown.space)="selectRoom(r); $event.preventDefault()"
                    >
                      <!-- Sin nombre encima: los planos reales ya traen los suyos y se enciman. Se ve al tocarlo. -->
                      <polygon [attr.points]="points(room)" vector-effect="non-scaling-stroke" />
                    </g>
                  }
                </svg>
              </div>

              <div class="detail" aria-live="polite">
                @if (room(); as selectedRoom) {
                  <p class="ov-eyebrow">{{ current.name }}</p>
                  <p class="room-name">{{ selectedRoom.name }}</p>
                  <p class="area ov-num">{{ selectedRoom.areaM2 | number: '1.0-2' }} m²</p>
                  <p class="source">
                    {{ selectedRoom.declaredArea ? 'Superficie del plano de arquitectura.' : 'Superficie según el dibujo.' }}
                  </p>
                } @else {
                  <p class="ov-lead">{{ current.rooms.length > 0 ? 'Elegí un ambiente del plano.' : (current.imageUrl ? 'Plano del estudio de arquitectura.' : 'Esta planta todavía no tiene ambientes dibujados.') }}</p>
                }
                @if (current.imageUrl) {
                  <a class="full ov-hit" [href]="current.imageUrl" target="_blank" rel="noopener">Ver el plano en tamaño completo</a>
                }
              </div>
            </div>
          }
        }
      </div>
    }
  `,
  styles: `
    .intro {
      display: grid;
      gap: var(--ov-space-3);
      margin-bottom: var(--ov-space-6);
    }

    .toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-4);
      margin-bottom: var(--ov-space-4);
    }

    .floors {
      display: inline-flex;
      gap: var(--ov-space-1);
      padding: var(--ov-space-1);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
    }

    .floor {
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .floor.active {
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .zoom {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .round {
      width: 2.25rem;
      height: 2.25rem;
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      cursor: pointer;
    }

    .round:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .level {
      min-width: 3rem;
      font-size: var(--ov-text-sm);
      text-align: center;
    }

    .canvas {
      display: grid;
      overflow: hidden;
    }

    .scroller {
      overflow: auto;
      padding: var(--ov-space-5);
    }

    svg {
      display: block;
      margin: 0 auto;
    }

    .grid-line {
      fill: none;
      stroke: var(--ov-line);
      stroke-width: 1;
      vector-effect: non-scaling-stroke;
    }

    .room polygon {
      fill: var(--ov-surface);
      stroke: var(--ov-ink);
      stroke-width: 1.5;
      transition: fill var(--ov-duration-fast) var(--ov-ease);
    }

    .room {
      cursor: pointer;
      outline: none;
    }

    .room:hover polygon {
      fill: var(--ov-canvas);
    }

    .room.selected polygon,
    .room:focus-visible polygon {
      fill: var(--ov-accent-soft);
      stroke: var(--ov-accent);
      stroke-width: 2.5;
    }

    /* Sobre el plano del estudio, los ambientes son una capa translúcida: el dibujo se sigue leyendo. */
    .with-image .room polygon {
      fill: color-mix(in srgb, var(--ov-accent) 8%, transparent);
      stroke: var(--ov-accent);
    }

    .with-image .room:hover polygon {
      fill: color-mix(in srgb, var(--ov-accent) 18%, transparent);
    }

    .with-image .room.selected polygon,
    .with-image .room:focus-visible polygon {
      fill: color-mix(in srgb, var(--ov-accent) 30%, transparent);
    }

    .full {
      margin-top: var(--ov-space-2);
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
    }

    .detail {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
    }

    .room-name {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-medium);
    }

    .area {
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
    }

    .source {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    @media (min-width: 56rem) {
      .canvas {
        grid-template-columns: minmax(0, 1fr) 16rem;
      }

      .detail {
        align-content: center;
        border-top: 0;
        border-left: 1px solid var(--ov-line);
      }
    }
  `,
})
export class PropertyPlansComponent {

  protected readonly store = inject(PropertyViewStore);

  protected readonly zoomSteps = ZOOM_STEPS;
  protected readonly planIndex = signal(0);
  protected readonly roomIndex = signal<number | null>(null);
  protected readonly zoomIndex = signal(1);

  protected readonly plan = computed(() => this.store.view()?.plans[this.planIndex()] ?? null);
  protected readonly room = computed(() => {
    const index = this.roomIndex();
    return index === null ? null : this.plan()?.rooms[index] ?? null;
  });
  protected readonly zoomLevel = computed(() => ZOOM_STEPS[this.zoomIndex()]);

  protected readonly gridX = computed(() => metersUpTo(this.plan()?.widthM ?? 0));
  protected readonly gridY = computed(() => metersUpTo(this.plan()?.depthM ?? 0));

  protected selectPlan(index: number): void {
    this.planIndex.set(index);
    this.roomIndex.set(null);
  }

  protected selectRoom(index: number): void {
    this.roomIndex.set(this.roomIndex() === index ? null : index);
  }

  protected zoom(direction: 1 | -1): void {
    const next = this.zoomIndex() + direction;
    if (next >= 0 && next < ZOOM_STEPS.length) {
      this.zoomIndex.set(next);
    }
  }

  protected points(room: PublicRoom): string {
    const pairs: string[] = [];
    for (let i = 0; i < room.polygon.length; i += 2) {
      pairs.push(`${room.polygon[i]},${room.polygon[i + 1]}`);
    }
    return pairs.join(' ');
  }

}
