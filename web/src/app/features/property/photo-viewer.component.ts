import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';

export interface ViewerPhoto {
  url: string;
  /** Línea debajo de la foto ampliada, por ejemplo su fecha. */
  caption?: string | null;
}

/**
 * Foto ampliada sobre un diálogo nativo, con anterior, siguiente y cerrar. Las flechas del
 * teclado también pasan de foto y Escape cierra (lo da el diálogo). Lo abre quien muestra
 * las miniaturas, con {@link open}.
 */
@Component({
  selector: 'app-photo-viewer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="lightbox" (close)="index.set(null)" (click)="close()" (keydown)="onKey($event)">
      @if (current(); as photo) {
        <img [src]="photo.url" [alt]="alt()" (click)="$event.stopPropagation()" (error)="failed.emit(photo.url)" />
        @if (photo.caption) {
          <p class="caption ov-num">{{ photo.caption }}</p>
        }
        <div class="lightbox-bar" (click)="$event.stopPropagation()">
          @if (photos().length > 1) {
            <button type="button" class="ov-btn ov-btn--on-media" (click)="step(-1)">Anterior</button>
            <span class="counter ov-num">{{ (index() ?? 0) + 1 }} de {{ photos().length }}</span>
            <button type="button" class="ov-btn ov-btn--on-media" (click)="step(1)">Siguiente</button>
          }
          <button type="button" class="ov-btn ov-btn--on-media" (click)="close()">Cerrar</button>
        </div>
      }
    </dialog>
  `,
  styles: `
    .lightbox {
      max-width: min(94vw, 84rem);
      padding: 0;
      border: 0;
      background: transparent;
    }

    .lightbox::backdrop {
      background: var(--ov-backdrop);
    }

    .lightbox img {
      display: block;
      max-width: 100%;
      max-height: 80vh;
      margin: 0 auto;
      border-radius: var(--ov-radius-md);
    }

    .caption {
      margin-top: var(--ov-space-2);
      color: var(--ov-on-media);
      font-size: var(--ov-text-sm);
      text-align: center;
    }

    .lightbox-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: center;
      gap: var(--ov-space-3);
      margin-top: var(--ov-space-4);
    }

    .counter {
      color: var(--ov-on-media);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class PhotoViewerComponent {

  readonly photos = input.required<ViewerPhoto[]>();
  readonly alt = input('Imagen ampliada');
  /** La foto ampliada no cargó, por ejemplo porque venció su URL firmada. */
  readonly failed = output<string>();

  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly index = signal<number | null>(null);

  protected readonly current = computed<ViewerPhoto | null>(() => {
    const index = this.index();
    return index === null ? null : this.photos()[index] ?? null;
  });

  open(index: number): void {
    this.index.set(index);
    this.dialog()?.nativeElement.showModal?.();
  }

  protected step(delta: number): void {
    const count = this.photos().length;
    if (count > 0) {
      this.index.update(index => ((index ?? 0) + delta + count) % count);
    }
  }

  protected close(): void {
    this.dialog()?.nativeElement.close?.();
    this.index.set(null);
  }

  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      this.step(event.key === 'ArrowLeft' ? -1 : 1);
    }
  }
}
