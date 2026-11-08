import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { SectorChipComponent } from '../../shared/ui/sector-chip.component';

/** Una foto elegida en "Publicar avance", tal como la dibuja la grilla. */
export interface GridPhoto {
  key: number;
  preview: string;
  state: 'uploading' | 'done' | 'failed';
  percent: number;
  /** Lugar de la casa que muestra; nulo: "General". */
  sector: string | null;
}

/**
 * Las fotos de "Publicar avance" en tres columnas, con "N de 10" arriba: cada una con su
 * progreso mientras sube, la marca cuando subió o "No subió" en ámbar con "Reintentar", y su
 * chip de lugar debajo. El último casillero es "Sacar o elegir", hasta llegar al tope. La
 * subida la maneja la pantalla: acá sólo se dibuja y se avisa.
 */
@Component({
  selector: 'app-field-photo-grid',
  imports: [MatIconModule, SectorChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <span class="label" id="field-photos-label">Fotos</span>
      <span class="count">{{ photos().length }} de {{ max() }}</span>
    </div>
    <ul class="grid" aria-labelledby="field-photos-label">
      @for (photo of photos(); track photo.key; let i = $index) {
        <li [attr.data-state]="photo.state">
          <div class="thumb">
            <img [src]="photo.preview" alt="" />
            @switch (photo.state) {
              @case ('uploading') {
                <span class="upload" role="progressbar" [attr.aria-valuenow]="photo.percent" aria-valuemin="0" aria-valuemax="100"
                      [attr.aria-label]="'Subiendo foto ' + (i + 1)">
                  <span [style.width.%]="photo.percent"></span>
                </span>
              }
              @case ('done') {
                <span class="ok" role="img" [attr.aria-label]="'Foto ' + (i + 1) + ' subida'">
                  <mat-icon svgIcon="check" aria-hidden="true" />
                </span>
              }
              @case ('failed') {
                <span class="failed">No subió</span>
                <button type="button" class="retry" (click)="retry.emit(photo.key)"
                        [attr.aria-label]="'Reintentar foto ' + (i + 1)">Reintentar</button>
              }
            }
            <button type="button" class="remove" (click)="remove.emit(photo.key)" [disabled]="disabled()"
                    [attr.aria-label]="'Quitar foto ' + (i + 1)">
              <span><mat-icon svgIcon="close" aria-hidden="true" /></span>
            </button>
          </div>
          @if (sectors().length > 0) {
            <app-sector-chip [sectors]="sectors()" [value]="photo.sector" [disabled]="disabled()"
                             [label]="'Lugar de la foto ' + (i + 1)" (changed)="sector.emit({ key: photo.key, sector: $event })" />
          }
        </li>
      }
      @if (photos().length < max()) {
        <li>
          <label class="add">
            <mat-icon svgIcon="photo_camera" aria-hidden="true" />
            Sacar o elegir
            <input type="file" accept="image/*" multiple [disabled]="disabled()" (change)="picked.emit($event)" />
          </label>
        </li>
      }
    </ul>
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--ov-space-3);
    }

    .head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
    }

    .label {
      font-weight: var(--ov-weight-semibold);
    }

    .count {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: grid;
      align-content: start;
      gap: var(--ov-space-1);
    }

    .thumb {
      position: relative;
      overflow: hidden;
      aspect-ratio: 1;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    li[data-state='uploading'] img,
    li[data-state='failed'] img {
      opacity: 0.55;
    }

    .upload {
      position: absolute;
      right: var(--ov-space-2);
      bottom: var(--ov-space-2);
      left: var(--ov-space-2);
      height: 0.375rem;
      overflow: hidden;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
    }

    .upload span {
      display: block;
      height: 100%;
      background: var(--ov-on-media);
      transition: width 120ms linear;
    }

    .ok {
      position: absolute;
      bottom: var(--ov-space-2);
      left: var(--ov-space-2);
      display: grid;
      place-items: center;
      width: 1.5rem;
      height: 1.5rem;
      border-radius: 50%;
      background: var(--ov-accent);
      color: var(--ov-on-media);
    }

    .ok mat-icon {
      width: 1rem;
      height: 1rem;
    }

    .failed {
      position: absolute;
      top: var(--ov-space-2);
      left: var(--ov-space-2);
      padding: 0 var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .retry {
      position: absolute;
      right: 0;
      bottom: 0;
      left: 0;
      min-height: 3rem;
      border: 0;
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    /* El área que se toca es de 48 px; el círculo que se ve, más chico, para no tapar la foto. */
    .remove {
      position: absolute;
      top: 0;
      right: 0;
      display: grid;
      place-items: center;
      width: 3rem;
      height: 3rem;
      padding: 0;
      border: 0;
      background: none;
      cursor: pointer;
    }

    .remove span {
      display: grid;
      place-items: center;
      width: 1.75rem;
      height: 1.75rem;
      border-radius: 50%;
      background: var(--ov-glass);
      color: var(--ov-on-media);
    }

    .remove mat-icon {
      width: 1.125rem;
      height: 1.125rem;
    }

    .add {
      position: relative;
      display: grid;
      place-content: center;
      justify-items: center;
      gap: var(--ov-space-1);
      aspect-ratio: 1;
      border: 1.5px dashed var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      text-align: center;
      cursor: pointer;
    }

    .add input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .add:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }
  `,
})
export class FieldPhotoGridComponent {
  readonly photos = input.required<GridPhoto[]>();
  readonly sectors = input<string[]>([]);
  readonly max = input(10);
  readonly disabled = input(false);
  readonly picked = output<Event>();
  readonly retry = output<number>();
  readonly remove = output<number>();
  readonly sector = output<{ key: number; sector: string | null }>();
}
