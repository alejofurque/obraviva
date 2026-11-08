import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, concatMap, from, map, of, toArray } from 'rxjs';
import { MediaAsset } from '../../core/api/api.models';
import { ProgressService } from '../../core/api/progress.service';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { errorMessageOf } from '../../shared/http/error-message';
import { todayLocal } from '../../shared/time/local-date';
import { selectPhotos } from './work-progress.component';

/**
 * Fotos de la galería de venta de una casa, de la más vieja a la más nueva. La marca es
 * explícita: una foto sin avance también puede ser de la sesión de recorrido, y esa no se vende.
 */
export function galleryOf(media: MediaAsset[], unitId: string): MediaAsset[] {
  return media
    .filter(asset => asset.kind === 'PHOTO' && asset.unitId === unitId && asset.showcase)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/**
 * "Galería de venta": renders e imágenes del modelo terminado, para mostrar la casa. No son
 * fotos de la obra (esas las suben los encargados) y por eso no tienen lugar ni entran en
 * "lugar por lugar". Si una foto de obra quedó acá por error, "Mover a fotos de obra" le pide
 * el lugar y el día en que se sacó y la pasa a donde corresponde.
 */
@Component({
  selector: 'app-sales-gallery',
  imports: [RouterLink, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card block" aria-labelledby="gallery-title">
      <div class="head">
        <div>
          <h3 id="gallery-title">Galería de venta</h3>
          <p class="muted">Renders e imágenes del modelo terminado. No son fotos de la obra: esas las suben los encargados.</p>
        </div>
        <label class="ov-btn ov-btn--secondary file">
          {{ uploading() ? 'Subiendo…' : 'Subir imágenes' }}
          <input type="file" accept="image/*" multiple (change)="add($event)" [disabled]="uploading()" />
        </label>
      </div>

      @if (gallery().length === 0) {
        <p class="muted">Todavía no hay imágenes. El comprador ve esta galería en "La casa", como imágenes del modelo.</p>
      } @else {
        <ul class="grid">
          @for (photo of gallery(); track photo.id) {
            <li class="tile">
              <div class="photo">
                <img [src]="photo | variant: 'thumb'" [alt]="'Imagen de la galería: ' + photo.originalFilename" loading="lazy" />
                <button type="button" class="remove" (click)="remove(photo)"
                        [attr.aria-label]="'Quitar ' + photo.originalFilename + ' de la galería'">Quitar</button>
              </div>
              <button type="button" class="ov-link move" [attr.aria-expanded]="moving()?.id === photo.id" (click)="startMove(photo)">
                Mover a fotos de obra
              </button>
            </li>
          }
        </ul>
      }

      @if (moving(); as photo) {
        <form class="move-form" (submit)="$event.preventDefault(); move(photo)" aria-labelledby="move-title">
          <p id="move-title" class="move-title">Mover {{ photo.originalFilename }} a fotos de obra</p>
          @if (sectors().length === 0) {
            <p class="muted">
              Esta casa todavía no tiene lugares. <a class="ov-link" [routerLink]="placesLink()">Definilos en Lugares y 3D</a>.
            </p>
          } @else {
            <div class="ov-field">
              <label class="ov-eyebrow" for="move-sector">Lugar</label>
              <select id="move-sector" class="ov-input" [value]="sector()" (change)="sector.set($any($event.target).value)">
                <option value="" disabled>Elegí el lugar</option>
                @for (name of sectors(); track name) {
                  <option [value]="name" [selected]="name === sector()">{{ name }}</option>
                }
              </select>
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="move-date">Día en que se sacó</label>
              <input id="move-date" class="ov-input" type="date" [max]="today" [value]="takenOn()"
                     (change)="takenOn.set($any($event.target).value)" />
            </div>
            <div class="actions">
              <button class="ov-btn ov-btn--primary" type="submit" [disabled]="!sector() || !takenOn() || busy()">
                {{ busy() ? 'Moviendo…' : 'Mover' }}
              </button>
              <button class="ov-link" type="button" (click)="moving.set(null)">Cancelar</button>
            </div>
          }
        </form>
      }

      @for (problem of problems(); track problem) {
        <p class="ov-error">{{ problem }}</p>
      }
    </section>
  `,
  styles: `
    .block {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
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

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .tile {
      display: grid;
      gap: var(--ov-space-1);
      align-content: start;
    }

    .photo {
      position: relative;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .photo img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .remove {
      position: absolute;
      right: var(--ov-space-2);
      bottom: var(--ov-space-2);
      padding: 0.1rem var(--ov-space-3);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .move {
      justify-self: start;
      font-size: var(--ov-text-sm);
    }

    .move-form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
      gap: var(--ov-space-3);
      padding: var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .move-title,
    .actions,
    .move-form > .muted {
      grid-column: 1 / -1;
    }

    .move-title {
      font-weight: var(--ov-weight-semibold);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }
  `,
})
export class SalesGalleryComponent implements OnInit {
  readonly developmentId = input.required<string>();
  readonly unitId = input.required<string>();
  /** Los lugares de la casa, para "Mover a fotos de obra". */
  readonly sectors = input<string[]>([]);
  readonly placesLink = input.required<string[]>();
  /** Cambió la galería: la casa recarga su resumen (la portada de respaldo, los pasos pendientes). */
  readonly changed = output<void>();

  private readonly progress = inject(ProgressService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly today = todayLocal();
  private readonly media = signal<MediaAsset[]>([]);
  protected readonly gallery = computed(() => galleryOf(this.media(), this.unitId()));
  protected readonly uploading = signal(false);
  protected readonly problems = signal<string[]>([]);
  protected readonly moving = signal<MediaAsset | null>(null);
  protected readonly sector = signal('');
  protected readonly takenOn = signal(this.today);
  protected readonly busy = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected add(event: Event): void {
    const input = event.target as HTMLInputElement;
    const { accepted, problems } = selectPhotos(Array.from(input.files ?? []));
    input.value = '';
    this.problems.set(problems);
    if (accepted.length === 0) {
      return;
    }
    this.uploading.set(true);
    from(accepted).pipe(
      // Material de venta: sin lugar.
      concatMap(file => this.progress.uploadGalleryPhoto(this.developmentId(), this.unitId(), file, null).pipe(
        map(() => null as string | null),
        catchError((error: unknown) => of(`${file.name}: ${errorMessageOf(error, 'no se pudo subir.')}`)),
      )),
      toArray(),
    ).subscribe(results => {
      this.uploading.set(false);
      this.problems.set([...problems, ...results.filter((result): result is string => result !== null)]);
      this.load();
      this.changed.emit();
    });
  }

  protected remove(photo: MediaAsset): void {
    if (!window.confirm('¿Quitar esta imagen de la galería?')) {
      return;
    }
    this.progress.deleteMedia(photo.id).subscribe({
      next: () => {
        this.media.update(media => media.filter(item => item.id !== photo.id));
        this.changed.emit();
      },
      error: (error: unknown) => this.problems.set([errorMessageOf(error, 'No se pudo quitar la imagen.')]),
    });
  }

  protected startMove(photo: MediaAsset): void {
    this.problems.set([]);
    this.sector.set('');
    this.takenOn.set(photo.takenOn ?? this.today);
    this.moving.set(this.moving()?.id === photo.id ? null : photo);
  }

  protected move(photo: MediaAsset): void {
    if (!this.sector() || !this.takenOn()) {
      return;
    }
    this.busy.set(true);
    this.progress.moveToWork(photo.id, this.sector(), this.takenOn()).subscribe({
      next: () => {
        this.busy.set(false);
        this.moving.set(null);
        this.media.update(media => media.filter(item => item.id !== photo.id));
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.problems.set([errorMessageOf(error, 'No se pudo mover la imagen.')]);
      },
    });
  }

  private load(): void {
    this.progress.media(this.developmentId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: media => this.media.set(media),
      error: (error: unknown) => this.problems.set([errorMessageOf(error, 'No se pudo cargar la galería.')]),
    });
  }
}
