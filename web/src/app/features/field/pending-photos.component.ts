import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ProgressService } from '../../core/api/progress.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { SectorChipComponent } from '../../shared/ui/sector-chip.component';
import { selectPhotos } from '../works/work-progress.component';
import { lastSector, rememberSector } from './sector-memory';

/** Lo que el formulario necesita saber de las fotos: sus ids, sus lugares y si todavía no se puede publicar. */
export interface PendingPhotosState {
  ids: string[];
  /** El lugar de cada foto subida, por su id; las que no tienen lugar no figuran. */
  sectors: Record<string, string>;
  busy: boolean;
  failed: boolean;
}

interface PendingPhoto {
  key: number;
  file: File;
  preview: string;
  state: 'uploading' | 'done' | 'failed';
  percent: number;
  mediaId: string | null;
  /** Lugar de la casa que muestra; nulo: "General". */
  sector: string | null;
}

/**
 * Fotos para un avance que todavía no existe: suben apenas se eligen, con su progreso, y
 * quedan pendientes hasta que el avance se publica con sus ids. Una que falla se reintenta
 * o se quita. Mismo flujo que "Publicar avance".
 */
@Component({
  selector: 'app-pending-photos',
  imports: [SectorChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (photos().length > 0) {
      <ul class="thumbs">
        @for (photo of photos(); track photo.key; let i = $index) {
          <li [attr.data-state]="photo.state">
            <div class="frame">
              <img [src]="photo.preview" alt="" />
              @if (photo.state === 'uploading') {
                <span class="upload" role="progressbar" aria-valuemin="0" aria-valuemax="100"
                      [attr.aria-valuenow]="photo.percent" [attr.aria-label]="'Subiendo foto ' + (i + 1)">
                  <span [style.width.%]="photo.percent"></span>
                </span>
              } @else if (photo.state === 'failed') {
                <span class="failed">No subió</span>
                <button type="button" class="chip retry" (click)="retry(photo.key)" [attr.aria-label]="'Reintentar foto ' + (i + 1)">Reintentar</button>
              }
              <button type="button" class="chip remove" [disabled]="disabled()" (click)="remove(photo.key)"
                      [attr.aria-label]="'Quitar foto ' + (i + 1)">Quitar</button>
            </div>
            @if (sectors().length > 0) {
              <app-sector-chip class="place" [sectors]="sectors()" [value]="photo.sector" [disabled]="disabled()"
                               [label]="'Lugar de la foto ' + (i + 1)" (changed)="chooseSector(photo.key, $event)" />
            }
          </li>
        }
      </ul>
    }
    @if (photos().length < maxPhotos) {
      <label class="ov-btn ov-btn--secondary ov-btn--block file">
        {{ photos().length === 0 ? 'Sumar fotos' : 'Agregar más fotos' }}
        <input type="file" accept="image/*" multiple [disabled]="disabled()" (change)="pick($event)" />
      </label>
    }
    @for (problem of problems(); track problem) {
      <p class="ov-error">{{ problem }}</p>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--ov-space-2);
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

    .thumbs {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    /* La foto en su marco cuadrado y, debajo, el chip de lugar opcional. */
    .thumbs li {
      display: grid;
      gap: var(--ov-space-1);
      align-content: start;
    }

    .frame {
      position: relative;
      overflow: hidden;
      aspect-ratio: 1;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
    }

    .thumbs img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .thumbs li[data-state='uploading'] img,
    .thumbs li[data-state='failed'] img {
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
      background: var(--ov-accent);
    }

    .failed {
      position: absolute;
      top: var(--ov-space-1);
      left: var(--ov-space-1);
      padding: 0 var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-danger);
      color: var(--ov-on-media);
      font-size: var(--ov-text-xs);
    }

    .chip {
      position: absolute;
      min-height: 1.75rem;
      padding: 0 var(--ov-space-2);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .remove {
      top: var(--ov-space-1);
      right: var(--ov-space-1);
    }

    .retry {
      bottom: var(--ov-space-1);
      left: var(--ov-space-1);
    }
  `,
})
export class PendingPhotosComponent {
  private readonly progress = inject(ProgressService);
  private readonly auth = inject(AuthService);

  readonly developmentId = input.required<string>();
  readonly unitId = input.required<string>();
  readonly disabled = input(false);
  /** Los lugares de la casa; sin ellos no se ofrece el chip. */
  readonly sectors = input<string[]>([]);
  readonly changed = output<PendingPhotosState>();

  protected readonly maxPhotos = 10;
  protected readonly photos = signal<PendingPhoto[]>([]);
  protected readonly problems = signal<string[]>([]);
  private nextKey = 0;
  private readonly uploads = new Map<number, Subscription>();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.uploads.forEach(upload => upload.unsubscribe());
      this.photos().forEach(photo => URL.revokeObjectURL(photo.preview));
    });
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const current = this.photos();
    const { accepted, problems } = selectPhotos([...current.map(photo => photo.file), ...Array.from(input.files ?? [])]);
    input.value = '';
    const added = accepted.slice(current.length).map(file => ({
      key: this.nextKey++, file, preview: URL.createObjectURL(file), state: 'uploading' as const, percent: 0, mediaId: null,
      sector: lastSector(this.auth.currentUser()?.id, this.unitId(), this.sectors()),
    }));
    this.photos.set([...current, ...added]);
    this.problems.set(problems);
    added.forEach(photo => this.upload(photo.key));
    this.emit();
  }

  /** El chip de la foto: el lugar queda recordado para las próximas fotos de esta casa. */
  protected chooseSector(key: number, sector: string | null): void {
    this.patch(key, { sector });
    rememberSector(this.auth.currentUser()?.id, this.unitId(), sector);
  }

  protected retry(key: number): void {
    this.patch(key, { state: 'uploading', percent: 0 });
    this.upload(key);
  }

  protected remove(key: number): void {
    this.uploads.get(key)?.unsubscribe();
    this.uploads.delete(key);
    const photo = this.photos().find(candidate => candidate.key === key);
    if (photo) {
      URL.revokeObjectURL(photo.preview);
    }
    this.photos.update(photos => photos.filter(candidate => candidate.key !== key));
    this.emit();
  }

  private upload(key: number): void {
    const photo = this.photos().find(candidate => candidate.key === key);
    if (!photo) {
      return;
    }
    this.uploads.set(key, this.progress.uploadPendingPhoto(this.developmentId(), this.unitId(), photo.file).subscribe({
      next: event => this.patch(key, event.kind === 'progress'
        ? { percent: event.percent }
        : { state: 'done', percent: 100, mediaId: event.mediaId }),
      error: (error: unknown) => {
        this.patch(key, { state: 'failed' });
        this.problems.set([errorMessageOf(error, 'Una foto no subió.')]);
      },
    }));
  }

  private patch(key: number, changes: Partial<PendingPhoto>): void {
    this.photos.update(photos => photos.map(photo => photo.key === key ? { ...photo, ...changes } : photo));
    this.emit();
  }

  private emit(): void {
    const photos = this.photos();
    this.changed.emit({
      ids: photos.filter(photo => photo.mediaId).map(photo => photo.mediaId!),
      sectors: Object.fromEntries(photos.filter(photo => photo.mediaId && photo.sector).map(photo => [photo.mediaId!, photo.sector!])),
      busy: photos.some(photo => photo.state === 'uploading'),
      failed: photos.some(photo => photo.state === 'failed'),
    });
  }
}
