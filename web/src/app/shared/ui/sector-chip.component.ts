import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * El lugar de la casa de una foto o video, como un chip: toca y abre el selector nativo
 * del dispositivo, que en el teléfono es el más cómodo y es accesible sin más. "Sin lugar"
 * deja la foto en "General".
 */
@Component({
  selector: 'app-sector-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <select class="chip" [class.chosen]="value()" [disabled]="disabled()" [attr.aria-label]="label()"
            (change)="changed.emit($any($event.target).value || null)">
      <option value="" [selected]="!value()">Sin lugar</option>
      @for (sector of sectors(); track sector) {
        <option [value]="sector" [selected]="sector === value()">{{ sector }}</option>
      }
      @if (value() && !sectors().includes(value()!)) {
        <!-- Un nombre que ya no es sector (anterior a los renombres) se muestra para no perderlo. -->
        <option [value]="value()" selected>{{ value() }}</option>
      }
    </select>
  `,
  styles: `
    :host {
      display: block;
    }

    .chip {
      width: 100%;
      min-height: 2.75rem;
      padding: 0 var(--ov-space-3);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .chip.chosen {
      border-color: var(--ov-accent);
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .chip:disabled {
      opacity: 0.5;
      cursor: default;
    }
  `,
})
export class SectorChipComponent {
  readonly sectors = input.required<string[]>();
  readonly value = input<string | null>(null);
  readonly label = input('Lugar de la foto');
  readonly disabled = input(false);
  readonly changed = output<string | null>();
}
