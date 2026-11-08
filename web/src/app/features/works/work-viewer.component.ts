import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Capture3d, CaptureTransformDto } from '../../core/api/api.models';
import { CapturesService } from '../../core/api/captures.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { AlignmentPanelComponent } from '../viewer/alignment-panel.component';
import { SplatViewerComponent, ViewerCapture } from '../viewer/splat-viewer.component';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

const IDENTITY: CaptureTransformDto = {
  position: { x: 0, y: 0, z: 0 },
  rotationDeg: { x: 0, y: 0, z: 0 },
  scale: 1,
};

/** Recorridos sin ambiente cargado: se agrupan juntos para no perderlos del selector. */
export const NO_ZONE = 'Sin ambiente';

/** Ambientes en el orden en que aparecen, con "Sin ambiente" al final si hace falta. */
export function viewerZones(captures: Pick<Capture3d, 'zone'>[]): string[] {
  const zones: string[] = [];
  for (const capture of captures) {
    const zone = capture.zone ?? NO_ZONE;
    if (!zones.includes(zone)) {
      zones.push(zone);
    }
  }
  return zones.sort((a, b) => (a === NO_ZONE ? 1 : b === NO_ZONE ? -1 : 0));
}

/**
 * Visor de la obra para quien la construye: todos los recorridos 3D por ambiente, el
 * viaje en el tiempo entre fechas y, para el dueño, la alineación de cada captura.
 *
 * <p>La cámara no se mueve al cambiar de fecha: el mundo cambia alrededor. Para que eso
 * funcione, las capturas de un mismo ambiente se alinean a mano contra la primera.
 */
@Component({
  selector: 'app-work-viewer',
  imports: [ShortDatePipe, RouterLink, SplatViewerComponent, AlignmentPanelComponent,
    LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page viewer-page">
      <header class="intro">
        <div>
          <a class="back ov-hit" [routerLink]="scope.link('recorridos')">Lugares y 3D</a>
          <h2>Visor de la obra</h2>
          <p class="muted">Arrastrá para mirar alrededor y usá la rueda o dos dedos para acercarte. Elegí una fecha para viajar en el tiempo.</p>
        </div>
        @if (store.isOwner() && active()) {
          <button class="ov-btn" [class.ov-btn--primary]="!aligning()" [class.ov-btn--secondary]="aligning()"
                  type="button" (click)="toggleAligning()">
            {{ aligning() ? 'Terminar de alinear' : 'Alinear esta captura' }}
          </button>
        }
      </header>

      @if (loading()) {
        <app-loading-state label="Cargando los recorridos…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (captures().length === 0) {
        <app-empty-state title="Todavía no hay recorridos disponibles"
          message="Cuando termine la reconstrucción de un video, se puede recorrer acá." />
      } @else {
        @if (zones().length > 1) {
          <div class="chips" role="radiogroup" aria-label="Ambiente">
            @for (name of zones(); track name) {
              <button type="button" role="radio" class="chip" [class.active]="name === zone()"
                      [attr.aria-checked]="name === zone()" (click)="selectZone(name)">{{ name }}</button>
            }
          </div>
        }

        <div class="stage" [class.with-panel]="aligning()">
          <div class="scene-frame">
            <app-splat-viewer #viewer class="scene" [captures]="viewerCaptures()" [activeIndex]="activeIndex()"
                              [crossfade]="true" />
            <button type="button" class="recenter" (click)="viewer.recenter()">Recentrar</button>
          </div>

          @if (aligning() && active(); as capture) {
            <aside class="ov-card panel" aria-labelledby="align-title">
              <h3 id="align-title">Alineación · {{ capture.captureDate | shortDate: 'd MMM y' }}</h3>
              <p class="muted">
                Mové esta captura hasta que coincida con las otras fechas del mismo ambiente. Cambiá de fecha para
                comparar sin mover la cámara.
              </p>
              <app-alignment-panel [transform]="capture.transform" (transformChange)="changeTransform($event)" />
              @if (dirty()) {
                <p class="unsaved" role="status">Hay cambios sin guardar.</p>
              }
              @if (saved()) {
                <p class="saved" role="status">Alineación guardada.</p>
              }
              @if (actionError(); as message) {
                <p class="ov-error" role="alert">{{ message }}</p>
              }
              <div class="actions">
                <button class="ov-btn ov-btn--primary" type="button" [disabled]="saving() || !dirty()" (click)="save()">
                  {{ saving() ? 'Guardando…' : 'Guardar' }}
                </button>
                <button class="ov-link" type="button" [disabled]="saving()" (click)="changeTransform(identity)">Restablecer</button>
              </div>
            </aside>
          }
        </div>

        <ol class="dates" aria-label="Fechas del recorrido">
          @for (capture of zoneCaptures(); track capture.id; let index = $index) {
            <li>
              <button type="button" class="date" [class.active]="index === activeIndex()"
                      [attr.aria-current]="index === activeIndex() ? 'true' : null" (click)="selectDate(index)">
                <span class="ov-num">{{ capture.captureDate | shortDate: 'd MMM y' }}</span>
                @if (capture.description) { <span class="date-detail">{{ capture.description }}</span> }
              </button>
            </li>
          }
        </ol>
      }
    </div>
  `,
  styles: `
    .viewer-page {
      display: grid;
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .chip,
    .date {
      border: 1px solid var(--ov-line);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      cursor: pointer;
    }

    .chip {
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border-radius: var(--ov-radius-pill);
      font-size: var(--ov-text-sm);
    }

    .chip.active,
    .date.active {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .chip:focus-visible,
    .date:focus-visible,
    .recenter:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .stage {
      display: grid;
      gap: var(--ov-space-4);
    }

    .scene-frame {
      position: relative;
      height: min(68vh, 42rem);
      overflow: hidden;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-ink);
    }

    .scene {
      display: block;
      width: 100%;
      height: 100%;
    }

    .recenter {
      position: absolute;
      right: var(--ov-space-4);
      bottom: var(--ov-space-4);
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-on-media-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-sm);
      backdrop-filter: blur(12px);
      cursor: pointer;
    }

    .panel {
      display: grid;
      align-content: start;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
    }

    .panel h3 {
      font-size: var(--ov-text-md);
    }

    .unsaved {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .saved {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .dates {
      display: flex;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0 0 var(--ov-space-2);
      overflow-x: auto;
      list-style: none;
    }

    .date {
      display: grid;
      gap: 2px;
      min-width: 9rem;
      padding: var(--ov-space-2) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      text-align: left;
    }

    .date-detail {
      overflow: hidden;
      max-width: 14rem;
      font-size: var(--ov-text-xs);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    @media (min-width: 64rem) {
      .stage.with-panel {
        grid-template-columns: minmax(0, 1fr) 22rem;
      }
    }
  `,
})
export class WorkViewerComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  private readonly capturesService = inject(CapturesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly identity = IDENTITY;
  protected readonly captures = signal<Capture3d[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly zone = signal<string | null>(null);
  protected readonly activeIndex = signal(0);
  protected readonly aligning = signal(false);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  /** Capturas con la transformación tocada y todavía no guardada. */
  private readonly dirtyIds = signal<ReadonlySet<string>>(new Set());

  protected readonly zones = computed(() => viewerZones(this.captures()));

  /** Capturas del ambiente elegido, en orden cronológico: el viaje en el tiempo es dentro de un lugar. */
  protected readonly zoneCaptures = computed(() => {
    const zone = this.zone();
    return this.captures()
      .filter(capture => capture.splatUrl !== null && (zone === null || (capture.zone ?? NO_ZONE) === zone))
      .sort((a, b) => a.captureDate.localeCompare(b.captureDate));
  });

  protected readonly viewerCaptures = computed<ViewerCapture[]>(() =>
    this.zoneCaptures().map(capture => ({ id: capture.id, url: capture.splatUrl!, transform: capture.transform })));

  protected readonly active = computed(() => this.zoneCaptures()[this.activeIndex()] ?? null);
  protected readonly dirty = computed(() => {
    const active = this.active();
    return active !== null && this.dirtyIds().has(active.id);
  });

  protected readonly scope = injectWorkScope();

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.capturesService.listByDevelopment(this.developmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: all => {
          // El viaje en el tiempo es de un lugar: la casa abierta, o las obras comunes.
          const captures = all.filter(capture => this.scope.owns(capture.unitId));
          this.captures.set(captures);
          const zones = viewerZones(captures);
          this.zone.set(zones.length > 1 ? zones[0] : null);
          // Se abre en la fecha más nueva: lo primero que se quiere ver es cómo está hoy.
          this.activeIndex.set(Math.max(0, this.zoneCaptures().length - 1));
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los recorridos.'));
        },
      });
  }

  protected selectZone(zone: string): void {
    this.zone.set(zone);
    this.activeIndex.set(Math.max(0, this.zoneCaptures().length - 1));
    this.saved.set(false);
  }

  protected selectDate(index: number): void {
    this.activeIndex.set(index);
    this.saved.set(false);
  }

  protected toggleAligning(): void {
    this.aligning.update(value => !value);
    this.saved.set(false);
    this.actionError.set(null);
  }

  /** Se aplica en memoria: el visor la refleja al instante y se guarda con el botón. */
  protected changeTransform(transform: CaptureTransformDto): void {
    const active = this.active();
    if (!active) {
      return;
    }
    this.captures.update(captures => captures.map(capture => (capture.id === active.id ? { ...capture, transform } : capture)));
    this.dirtyIds.update(ids => new Set(ids).add(active.id));
    this.saved.set(false);
  }

  protected save(): void {
    const active = this.active();
    if (!active) {
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    this.capturesService.updateTransform(active.id, active.transform).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.dirtyIds.update(ids => {
          const next = new Set(ids);
          next.delete(active.id);
          return next;
        });
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.actionError.set(errorMessageOf(error, 'No se pudo guardar la alineación.'));
      },
    });
  }
}
