import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { elapsedLabel } from './audio-review';

const BARS = 24;

/**
 * El nivel de una muestra del micrófono, de 0 a 1: la raíz cuadrática media de la onda, que
 * llega centrada en 128. Amplificado para que una voz normal mueva las barras.
 */
export function levelOf(samples: Uint8Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (const value of samples) {
    const centered = (value - 128) / 128;
    sum += centered * centered;
  }
  return Math.min(1, Math.sqrt(sum / samples.length) * 4);
}

/**
 * Escucha el nivel de un micrófono con Web Audio (AnalyserNode) unas doce veces por segundo.
 * Devuelve cómo dejar de escuchar. Sin Web Audio (un navegador viejo, los tests), no hace nada.
 */
export function listenLevel(stream: MediaStream, onLevel: (level: number) => void): () => void {
  const Context = globalThis.AudioContext ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Context || typeof requestAnimationFrame !== 'function') return () => undefined;
  const context = new Context();
  const source = context.createMediaStreamSource(stream);
  const analyser = context.createAnalyser();
  analyser.fftSize = 512;
  source.connect(analyser);
  const samples = new Uint8Array(analyser.fftSize);
  let frame = 0;
  let last = 0;
  const tick = (now: number) => {
    frame = requestAnimationFrame(tick);
    if (now - last < 80) return;
    last = now;
    analyser.getByteTimeDomainData(samples);
    onLevel(levelOf(samples));
  };
  frame = requestAnimationFrame(tick);
  return () => {
    cancelAnimationFrame(frame);
    source.disconnect();
    void context.close();
  };
}

/**
 * Grabar en "Contarlo con audio": la onda con el nivel real del micrófono mientras se graba
 * (quieta y gris si no), el tiempo y un botón redondo de 96 px que se toca sin mirar: verde
 * para grabar, negro con un cuadrado para terminar. La grabación la maneja la pantalla.
 */
@Component({
  selector: 'app-field-recorder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wave" [class.live]="recording()" aria-hidden="true">
      @for (level of levels(); track $index) {
        <span [style.height.px]="6 + level * 42"></span>
      }
    </div>
    <p class="clock ov-num" aria-label="Tiempo grabado">{{ clock() }}</p>
    <button type="button" class="record" [class.on]="recording()"
            [attr.aria-label]="recording() ? 'Terminar de grabar' : 'Grabar'" (click)="toggle.emit()">
      <span></span>
    </button>
    <p class="hint" aria-live="polite">{{ recording() ? 'Tocá para terminar' : 'Tocá para grabar' }}</p>
  `,
  styles: `
    :host {
      display: grid;
      justify-items: center;
      gap: var(--ov-space-4);
    }

    .wave {
      display: flex;
      align-items: center;
      gap: 4px;
      height: 3.5rem;
    }

    .wave span {
      width: 4px;
      border-radius: 2px;
      background: var(--ov-line-strong);
      transition: height 80ms linear;
    }

    .wave.live span {
      background: var(--ov-accent);
    }

    .clock {
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
    }

    .record {
      display: grid;
      place-items: center;
      width: 6rem;
      height: 6rem;
      border: 0;
      border-radius: 50%;
      background: var(--ov-accent);
      box-shadow: 0 8px 24px color-mix(in srgb, var(--ov-accent) 35%, transparent);
      cursor: pointer;
    }

    .record span {
      width: 2.125rem;
      height: 2.125rem;
      border-radius: 50%;
      background: var(--ov-surface);
    }

    .record.on {
      background: var(--ov-ink);
      box-shadow: 0 0 0 10px color-mix(in srgb, var(--ov-accent) 18%, transparent);
    }

    .record.on span {
      width: 1.875rem;
      height: 1.875rem;
      border-radius: var(--ov-radius-sm);
    }

    .record:focus-visible {
      outline: 3px solid var(--ov-accent);
      outline-offset: 4px;
    }

    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class FieldRecorderComponent {
  readonly recording = input(false);
  readonly seconds = input(0);
  /** El micrófono mientras se graba; nulo, la onda queda quieta. */
  readonly stream = input<MediaStream | null>(null);
  readonly toggle = output<void>();

  protected readonly levels = signal<number[]>(Array(BARS).fill(0));
  protected readonly clock = computed(() => elapsedLabel(this.seconds()));

  constructor() {
    effect(onCleanup => {
      const stream = this.stream();
      this.levels.set(Array(BARS).fill(0));
      if (!stream) return;
      onCleanup(listenLevel(stream, level => this.levels.update(levels => [...levels.slice(1), level])));
    });
  }
}
