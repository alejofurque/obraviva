import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Capture3d, MediaAsset, Stage, CaptureUpdatePayload } from '../../core/api/api.models';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { SectorChipComponent } from '../../shared/ui/sector-chip.component';
import { PlaceCell, monthLong } from './place-grid';
import { countLabel } from './work-labels';
import { processingLabel } from './capture-labels';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * El detalle de una celda de "Lugares y 3D": los recorridos de ese lugar ese mes (con
 * "Editar datos"), los que se reconstruyen y las fotos y videos, cada uno con el chip para
 * pasarlo a otro lugar y "Borrar". Son las mismas acciones de siempre, ahora por celda.
 */
@Component({
  selector: 'app-place-detail',
  imports: [ShortDatePipe, ReactiveFormsModule, RouterLink, VariantPipe, SectorChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = cell();
    <section class="ov-card detail" aria-labelledby="detail-title">
      <div class="head">
        <h3 id="detail-title">{{ name() }} · {{ month() }}</h3>
        @if (current.tours.length > 0 && viewerLink(); as link) {
          <a class="ov-link" [routerLink]="link">Abrir en el visor</a>
        }
      </div>

      @if (empty()) {
        <p class="muted">Este mes no hay recorrido ni fotos de {{ name() }}.</p>
      } @else {
        <div class="items">
          @for (capture of current.tours; track capture.id) {
            <div class="tour">
              <span class="tag">Recorrido 3D · {{ capture.captureDate | shortDate: 'd MMM' }}</span>
              @if (capture.description) { <span class="tour-text">{{ capture.description }}</span> }
            </div>
          }
          @for (capture of current.building; track capture.captureId) {
            <div class="tour building">
              <span class="tag">Reconstruyendo · {{ capture.captureDate | shortDate: 'd MMM' }}</span>
              <span class="tour-text">{{ stepLabel(capture) }}</span>
            </div>
          }
          @for (asset of current.media; track asset.id) {
            <figure class="tile">
              @if (asset.kind === 'VIDEO') {
                <video [src]="asset.downloadUrl" [poster]="asset | variant: 'poster'" preload="metadata" muted playsinline></video>
              } @else {
                <img [src]="asset | variant: 'thumb'" alt="" loading="lazy" />
              }
              <figcaption>
                <span class="muted ov-num">{{ (asset.takenOn ?? asset.createdAt) | shortDate: 'd MMM' }}</span>
                @if (sectors().length > 0) {
                  <app-sector-chip [sectors]="sectors()" [value]="asset.sector" [disabled]="busyMediaId() === asset.id"
                                   [label]="'Lugar de ' + asset.originalFilename" (changed)="moveMedia.emit({ asset, sector: $event })" />
                }
                @if (owner() || asset.mine) {
                  <button class="ov-link" type="button" [disabled]="busyMediaId() === asset.id" (click)="deleteMedia.emit(asset)">Borrar</button>
                }
              </figcaption>
            </figure>
          }
        </div>

        <p class="foot">
          <span>{{ summary() }}</span>
          @for (capture of current.tours; track capture.id) {
            <button class="ov-link" type="button" (click)="startEdit(capture)">
              Editar datos del recorrido{{ current.tours.length > 1 ? ' del ' + (capture.captureDate | shortDate: 'd MMM') : '' }}
            </button>
          }
        </p>

        @if (editing(); as capture) {
          <form [formGroup]="form" (ngSubmit)="save(capture)" class="edit" novalidate>
            <div class="ov-field">
              <label class="ov-eyebrow" [for]="'edit-date-' + capture.id">Fecha</label>
              <input class="ov-input" type="date" [id]="'edit-date-' + capture.id" formControlName="captureDate" [max]="today()" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" [for]="'edit-zone-' + capture.id">Lugar</label>
              <input class="ov-input" [id]="'edit-zone-' + capture.id" formControlName="zone" list="zones" maxlength="80" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" [for]="'edit-stage-' + capture.id">Etapa</label>
              <select class="ov-input" [id]="'edit-stage-' + capture.id" formControlName="stageId">
                <option value="">Sin etapa</option>
                @for (stage of stages(); track stage.id) {
                  <option [value]="stage.id">{{ stage.name }}</option>
                }
              </select>
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" [for]="'edit-description-' + capture.id">Descripción</label>
              <input class="ov-input" [id]="'edit-description-' + capture.id" formControlName="description" maxlength="500" />
            </div>
            <div class="actions">
              <button class="ov-btn ov-btn--primary" type="submit" [disabled]="form.invalid">Guardar</button>
              <button class="ov-link" type="button" (click)="editing.set(null)">Cancelar</button>
            </div>
          </form>
        }
      }
    </section>
  `,
  styles: `
    .detail {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .head,
    .foot {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
    }

    .foot {
      justify-content: flex-start;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .items {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-3);
    }

    .tour {
      display: flex;
      flex: 0 0 13.75rem;
      flex-direction: column;
      justify-content: space-between;
      gap: var(--ov-space-2);
      height: 9.5rem;
      padding: var(--ov-space-3);
      border-radius: var(--ov-radius-md);
      background: color-mix(in srgb, var(--ov-accent) 40%, var(--ov-ink));
      color: var(--ov-on-media);
    }

    .tour.building {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .tag {
      align-self: flex-start;
      padding: 0.1rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: color-mix(in srgb, var(--ov-on-media) 18%, transparent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .tour-text {
      font-size: var(--ov-text-sm);
    }

    .tile {
      display: grid;
      gap: var(--ov-space-1);
      width: 9.5rem;
      margin: 0;
    }

    .tile img,
    .tile video {
      width: 100%;
      height: 9.5rem;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      object-fit: cover;
    }

    figcaption {
      display: grid;
      gap: var(--ov-space-1);
    }

    .edit {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: var(--ov-space-3);
    }

    .actions {
      display: flex;
      grid-column: 1 / -1;
      align-items: center;
      gap: var(--ov-space-4);
    }

    @media (max-width: 40rem) {
      .tour,
      .tile {
        flex-basis: calc(50% - var(--ov-space-2));
        width: calc(50% - var(--ov-space-2));
      }
    }
  `,
})
export class PlaceDetailComponent {
  readonly name = input.required<string>();
  readonly cell = input.required<PlaceCell>();
  readonly sectors = input<string[]>([]);
  readonly stages = input<Stage[]>([]);
  readonly owner = input(false);
  readonly busyMediaId = input<string | null>(null);
  readonly viewerLink = input<string[] | null>(null);
  readonly today = input.required<string>();

  readonly moveMedia = output<{ asset: MediaAsset; sector: string | null }>();
  readonly deleteMedia = output<MediaAsset>();
  readonly saveCapture = output<{ capture: Capture3d; changes: CaptureUpdatePayload }>();

  private readonly formBuilder = inject(FormBuilder);
  protected readonly editing = signal<Capture3d | null>(null);
  protected readonly stepLabel = processingLabel;

  protected readonly month = computed(() => monthLong(this.cell().month));
  protected readonly empty = computed(() => {
    const cell = this.cell();
    return cell.tours.length === 0 && cell.building.length === 0 && cell.media.length === 0;
  });
  protected readonly summary = computed(() => {
    const cell = this.cell();
    const parts: string[] = [];
    if (cell.tours.length > 0) parts.push(countLabel(cell.tours.length, 'recorrido', 'recorridos'));
    if (cell.media.length > 0) parts.push(countLabel(cell.media.length, 'foto o video', 'fotos y videos'));
    return parts.join(' · ');
  });

  protected readonly form = this.formBuilder.nonNullable.group({
    captureDate: ['', Validators.required],
    zone: [''],
    stageId: [''],
    description: [''],
  });

  protected startEdit(capture: Capture3d): void {
    this.editing.set(capture);
    this.form.setValue({
      captureDate: capture.captureDate,
      zone: capture.zone ?? '',
      stageId: capture.stageId ?? '',
      description: capture.description ?? '',
    });
  }

  protected save(capture: Capture3d): void {
    const value = this.form.getRawValue();
    this.saveCapture.emit({
      capture,
      changes: {
        captureDate: value.captureDate,
        zone: value.zone.trim() || null,
        stageId: value.stageId || null,
        description: value.description.trim() || null,
      },
    });
    this.editing.set(null);
  }
}
