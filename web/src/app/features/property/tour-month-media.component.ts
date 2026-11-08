import { formatDate } from '@angular/common';
import { ChangeDetectionStrategy, Component, LOCALE_ID, computed, inject, input, output, signal } from '@angular/core';
import { VariantPipe, variantOf } from '../../shared/media/variant.pipe';
import { PublicMedia, PublicPlaceMonth } from '../../core/api/api.models';
import { mediaDay } from './places-derivations';
import { PhotoViewerComponent, ViewerPhoto } from './photo-viewer.component';

/**
 * "Fotos de este mes" dentro del recorrido 3D: lo que el lugar y el mes elegidos tienen en
 * fotos y videos, plegado para no tapar la escena. Sale de `places`, sin otro pedido.
 */
@Component({
  selector: 'app-tour-month-media',
  imports: [PhotoViewerComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="toggle" [attr.aria-expanded]="open()" aria-controls="month-media"
            (click)="open.set(!open())">
      Fotos de este mes · {{ media().photos.length + media().videos.length }}
    </button>
    @if (open()) {
      <ul id="month-media" class="sheet" [attr.aria-label]="'Fotos y videos de ' + place()">
        @for (photo of media().photos; track photo.url; let i = $index) {
          <li>
            <button type="button" class="shot" (click)="viewer.open(i)" [attr.aria-label]="'Ampliar ' + caption(photo)">
              <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" (error)="failed.emit(photo.url)" />
            </button>
          </li>
        }
        @for (video of media().videos; track video.url) {
          <li>
            <video class="shot" [src]="video.url" [poster]="video | variant: 'poster'" controls preload="metadata" playsinline [attr.aria-label]="'Video del ' + caption(video)"></video>
          </li>
        }
      </ul>
    }
    <app-photo-viewer #viewer [photos]="viewerPhotos()" [alt]="'Foto de ' + place() + ' ampliada'" (failed)="failed.emit($event)" />
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--ov-space-2);
      justify-items: start;
    }

    .toggle {
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-sm);
      backdrop-filter: blur(12px);
      cursor: pointer;
    }

    /* Una hoja baja, que se desplaza de costado: la escena sigue a la vista. */
    .sheet {
      display: flex;
      gap: var(--ov-space-2);
      width: 100%;
      margin: 0;
      padding: var(--ov-space-3);
      overflow-x: auto;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-glass);
      backdrop-filter: blur(16px);
      list-style: none;
    }

    .shot {
      display: block;
      width: 7.5rem;
      padding: 0;
      overflow: hidden;
      border: 0;
      border-radius: var(--ov-radius-md);
      background: var(--ov-veil);
      aspect-ratio: 4 / 3;
      object-fit: cover;
      cursor: zoom-in;
    }

    .shot img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  `,
})
export class TourMonthMediaComponent {

  readonly media = input.required<PublicPlaceMonth>();
  readonly place = input.required<string>();
  readonly failed = output<string>();

  private readonly locale = inject(LOCALE_ID);
  protected readonly open = signal(false);

  protected caption(media: PublicMedia): string {
    return formatDate(mediaDay(media), 'longDate', this.locale);
  }

  protected readonly viewerPhotos = computed<ViewerPhoto[]>(() =>
    this.media().photos.map(photo => ({ url: variantOf(photo, 'screen')!, caption: this.caption(photo) })));
}
