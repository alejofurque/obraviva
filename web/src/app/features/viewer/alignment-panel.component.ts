import { ChangeDetectionStrategy, Component, model } from '@angular/core';
import { CaptureTransformDto } from '../../core/api/api.models';

type Axis = 'x' | 'y' | 'z';

/**
 * Ajuste manual de la transformación de una captura: posición, rotación y escala. La
 * alineación entre capturas es manual por decisión del documento maestro; el registro 3D
 * automático es un problema de investigación que no hace falta para el producto.
 */
@Component({
  selector: 'app-alignment-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset>
      <legend class="ov-eyebrow">Posición (m)</legend>
      @for (axis of axes; track axis) {
        <div class="row">
          <label [for]="'position-' + axis">{{ axis.toUpperCase() }}</label>
          <input type="range" [id]="'position-' + axis" min="-10" max="10" step="0.05"
                 [value]="transform().position[axis]" (input)="setPosition(axis, $event)" />
          <span class="value ov-num">{{ transform().position[axis].toFixed(2) }}</span>
        </div>
      }
    </fieldset>

    <fieldset>
      <legend class="ov-eyebrow">Rotación (grados)</legend>
      @for (axis of axes; track axis) {
        <div class="row">
          <label [for]="'rotation-' + axis">{{ axis.toUpperCase() }}</label>
          <input type="range" [id]="'rotation-' + axis" min="-180" max="180" step="1"
                 [value]="transform().rotationDeg[axis]" (input)="setRotation(axis, $event)" />
          <span class="value ov-num">{{ transform().rotationDeg[axis].toFixed(0) }}°</span>
        </div>
      }
    </fieldset>

    <fieldset>
      <legend class="ov-eyebrow">Escala</legend>
      <div class="row">
        <label for="scale">S</label>
        <input type="range" id="scale" min="0.1" max="5" step="0.05"
               [value]="transform().scale" (input)="setScale($event)" />
        <span class="value ov-num">{{ transform().scale.toFixed(2) }}</span>
      </div>
    </fieldset>
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--ov-space-4);
    }

    fieldset {
      display: grid;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      border: 0;
    }

    legend {
      margin-bottom: var(--ov-space-1);
    }

    .row {
      display: grid;
      grid-template-columns: 1.25rem 1fr 3.5rem;
      align-items: center;
      gap: var(--ov-space-3);
    }

    label {
      font-weight: var(--ov-weight-semibold);
    }

    input[type='range'] {
      width: 100%;
      accent-color: var(--ov-accent);
    }

    .value {
      text-align: right;
      font-size: var(--ov-text-sm);
    }
  `,
})
export class AlignmentPanelComponent {

  readonly transform = model.required<CaptureTransformDto>();

  protected readonly axes: Axis[] = ['x', 'y', 'z'];

  protected setPosition(axis: Axis, event: Event): void {
    const current = this.transform();
    this.transform.set({ ...current, position: { ...current.position, [axis]: valueOf(event) } });
  }

  protected setRotation(axis: Axis, event: Event): void {
    const current = this.transform();
    this.transform.set({ ...current, rotationDeg: { ...current.rotationDeg, [axis]: valueOf(event) } });
  }

  protected setScale(event: Event): void {
    this.transform.set({ ...this.transform(), scale: valueOf(event) });
  }
}

function valueOf(event: Event): number {
  return Number((event.target as HTMLInputElement).value);
}
