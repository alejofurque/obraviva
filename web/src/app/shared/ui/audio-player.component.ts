import { ChangeDetectionStrategy, Component, ElementRef, input, signal, viewChild } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** "1:05": minutos y segundos, sin horas (un parte de obra no dura una hora). */
export function clockOf(seconds: number): string {
  const whole = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

/**
 * Reproductor de audio con los tokens de la aplicación, en vez de los controles del
 * navegador (cada uno los dibuja distinto y ninguno con nuestros colores). Un botón de
 * 44 px para reproducir o pausar, la posición como un rango nativo (se maneja con teclado
 * y lector de pantalla) y el tiempo transcurrido sobre el total.
 */
@Component({
  selector: 'app-audio-player',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <audio #audio [src]="src()" preload="metadata"
           (loadedmetadata)="duration.set(audio.duration)" (durationchange)="duration.set(audio.duration)"
           (timeupdate)="position.set(audio.currentTime)" (play)="playing.set(true)" (pause)="playing.set(false)"
           (ended)="playing.set(false)"></audio>
    <button type="button" class="toggle" (click)="toggle()" [attr.aria-label]="playing() ? 'Pausar' : 'Escuchar ' + label()">
      <mat-icon [svgIcon]="playing() ? 'pause' : 'play_arrow'" aria-hidden="true" />
    </button>
    <input type="range" class="seek" min="0" [max]="duration() || 0" step="0.1" [value]="position()"
           [style.--done.%]="duration() ? (position() / duration()) * 100 : 0"
           [attr.aria-label]="'Posición en ' + label()" [attr.aria-valuetext]="clock(position()) + ' de ' + clock(duration())"
           (input)="seek($event)" />
    <span class="ov-num time">{{ clock(position()) }} / {{ clock(duration()) }}</span>
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-2) var(--ov-space-4) var(--ov-space-2) var(--ov-space-2);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .toggle {
      display: grid;
      flex: none;
      place-items: center;
      width: 2.75rem;
      height: 2.75rem;
      padding: 0;
      border: 0;
      border-radius: 50%;
      background: var(--ov-ink);
      color: var(--ov-surface);
      cursor: pointer;
    }

    .toggle:focus-visible,
    .seek:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .seek {
      flex: 1;
      min-width: 0;
      height: 2.75rem;
      margin: 0;
      background: transparent;
      appearance: none;
      cursor: pointer;
    }

    .seek::-webkit-slider-runnable-track {
      height: 0.25rem;
      border-radius: var(--ov-radius-pill);
      background: linear-gradient(to right, var(--ov-accent) var(--done, 0%), var(--ov-line) var(--done, 0%));
    }

    .seek::-moz-range-track {
      height: 0.25rem;
      border-radius: var(--ov-radius-pill);
      background: linear-gradient(to right, var(--ov-accent) var(--done, 0%), var(--ov-line) var(--done, 0%));
    }

    .seek::-webkit-slider-thumb {
      width: 0.875rem;
      height: 0.875rem;
      margin-top: -0.3125rem;
      border: 0;
      border-radius: 50%;
      background: var(--ov-accent);
      appearance: none;
    }

    .seek::-moz-range-thumb {
      width: 0.875rem;
      height: 0.875rem;
      border: 0;
      border-radius: 50%;
      background: var(--ov-accent);
    }

    .time {
      flex: none;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class AudioPlayerComponent {
  readonly src = input.required<string>();
  /** Qué se escucha, para el lector de pantalla: "el parte". */
  readonly label = input('el audio');

  private readonly audio = viewChild.required<ElementRef<HTMLAudioElement>>('audio');
  protected readonly playing = signal(false);
  protected readonly position = signal(0);
  protected readonly duration = signal(0);
  protected readonly clock = clockOf;

  protected toggle(): void {
    const audio = this.audio().nativeElement;
    if (audio.paused) {
      void audio.play().catch(() => this.playing.set(false));
    } else {
      audio.pause();
    }
  }

  protected seek(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.audio().nativeElement.currentTime = value;
    this.position.set(value);
  }
}
