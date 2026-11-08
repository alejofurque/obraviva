import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * "Reconstrucción 3D", para el dueño: cuántos créditos le quedan a la cuenta del servicio,
 * leídos de su saldo. En modo simulado lo dice en vez de un número; si el servicio no
 * respondió, no hay número. Un video por lugar consume un crédito; un archivo de Scaniverse, no.
 */
@Component({
  selector: 'app-reconstruction-credits',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card credits" aria-labelledby="credits-title">
      <h3 id="credits-title">Reconstrucción 3D</h3>
      @if (simulated()) {
        <p class="state">Modo simulado: los videos no consumen créditos.</p>
      } @else if (credits() !== null) {
        <p class="amount"><span class="ov-num value">{{ credits() }}</span> {{ credits() === 1 ? 'crédito disponible' : 'créditos disponibles' }}</p>
      } @else if (loaded()) {
        <p class="state">No se pudo consultar el saldo del servicio.</p>
      }
      <p class="muted">Cada video por lugar consume 1 crédito. Los archivos de Scaniverse no consumen.</p>
      <button type="button" class="ov-link" (click)="upload.emit()">Subir un video suelto</button>
    </section>
  `,
  styles: `
    .credits {
      display: grid;
      align-content: start;
      gap: var(--ov-space-2);
      padding: var(--ov-space-5);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .amount {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .value {
      margin-right: var(--ov-space-1);
      color: var(--ov-ink);
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
    }

    .state {
      font-weight: var(--ov-weight-medium);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      line-height: 1.45;
    }

    button {
      justify-self: start;
    }
  `,
})
export class ReconstructionCreditsComponent {
  readonly simulated = input(false);
  readonly credits = input<number | null>(null);
  /** Ya respondió el servidor: sin eso, "no se pudo" sería prematuro. */
  readonly loaded = input(false);
  readonly upload = output<void>();
}
