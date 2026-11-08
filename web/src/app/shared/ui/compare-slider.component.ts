import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

/**
 * Dos imágenes del mismo lugar, una sobre otra, con un deslizador que corre el corte.
 * Es la forma más directa de ver el proyecto contra la realidad: el ojo compara el mismo
 * encuadre sin ir y venir entre dos fotos.
 *
 * <p>El control es un input de rango nativo: funciona con teclado, lector de pantalla y
 * dedo sin código propio de arrastre.
 */
@Component({
  selector: 'app-compare-slider',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="frame" [style.--split.%]="split()">
      <img class="after" [src]="afterUrl()" [alt]="afterLabel()" loading="lazy" />
      <img class="before" [src]="beforeUrl()" [alt]="beforeLabel()" loading="lazy" />
      <span class="tag tag-before" aria-hidden="true">{{ beforeLabel() }}</span>
      <span class="tag tag-after" aria-hidden="true">{{ afterLabel() }}</span>
      <span class="divider" aria-hidden="true"></span>
      <input type="range" min="0" max="100" step="1" [value]="split()" (input)="move($event)"
             [attr.aria-label]="'Deslizá para comparar ' + beforeLabel() + ' con ' + afterLabel()" />
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    /* Quien lo usa puede topear el alto: el ancho se mantiene y las imágenes se recortan. */
    .frame {
      position: relative;
      width: 100%;
      aspect-ratio: 4 / 3;
      max-height: var(--compare-max-height, none);
      overflow: hidden;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface-muted);
    }

    img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .before {
      clip-path: inset(0 calc(100% - var(--split)) 0 0);
    }

    .divider {
      position: absolute;
      top: 0;
      bottom: 0;
      left: var(--split);
      width: 2px;
      background: var(--ov-on-media);
      box-shadow: 0 0 0 1px rgb(0 0 0 / 0.25);
      transform: translateX(-1px);
      pointer-events: none;
    }

    .tag {
      position: absolute;
      top: var(--ov-space-3);
      padding: var(--ov-space-1) var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
      backdrop-filter: blur(10px);
      pointer-events: none;
    }

    .tag-before {
      left: var(--ov-space-3);
    }

    .tag-after {
      right: var(--ov-space-3);
    }

    input[type='range'] {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      margin: 0;
      opacity: 0;
      cursor: ew-resize;
    }

    .frame:has(input:focus-visible) {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }
  `,
})
export class CompareSliderComponent {

  readonly beforeUrl = input.required<string>();
  readonly afterUrl = input.required<string>();
  readonly beforeLabel = input('Proyecto');
  readonly afterLabel = input('Hoy');

  protected readonly split = signal(50);

  protected move(event: Event): void {
    this.split.set(Number((event.target as HTMLInputElement).value));
  }
}
