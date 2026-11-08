import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Marca de ObraViva: la palabra, con "Viva" en el verde de obra. Sobre el verde de obra
 * (`inverse`), toda en blanco: el verde sobre verde no se lee.
 */
@Component({
  selector: 'app-brand',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="obra">Obra</span><span class="viva">Viva</span>`,
  host: { 'aria-label': 'ObraViva', role: 'img', '[class.inverse]': 'inverse()' },
  styles: `
    :host {
      display: inline-flex;
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
      line-height: 1;
    }

    .obra {
      color: var(--ov-ink);
    }

    .viva {
      color: var(--ov-accent);
    }

    :host(.inverse) .obra,
    :host(.inverse) .viva {
      color: var(--ov-on-ink);
    }
  `,
})
export class BrandComponent {
  readonly inverse = input(false);
}
