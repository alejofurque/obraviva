import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Los tres estados que toda pantalla con datos tiene que resolver: cargando, vacía y
 * con error. Están acá para que se vean y se lean igual en cualquier sección.
 */

/** Carga en curso. Se anuncia a lectores de pantalla sin robar el foco. */
@Component({
  selector: 'app-loading-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="track" aria-hidden="true"><div class="bar"></div></div>
    <p>{{ label() }}</p>
  `,
  host: { role: 'status', 'aria-live': 'polite' },
  styles: `
    :host {
      display: grid;
      justify-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-7) var(--ov-space-4);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .track {
      position: relative;
      width: 8rem;
      height: 3px;
      overflow: hidden;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-line);
    }

    .bar {
      position: absolute;
      inset: 0 auto 0 0;
      width: 40%;
      border-radius: inherit;
      background: var(--ov-accent);
      animation: slide 1.1s var(--ov-ease) infinite;
    }

    @keyframes slide {
      from { transform: translateX(-100%); }
      to { transform: translateX(250%); }
    }
  `,
})
export class LoadingStateComponent {
  readonly label = input('Cargando…');
}

/**
 * Sin datos. Una pantalla vacía es una invitación a actuar: dice qué falta y ofrece
 * la acción que lo resuelve, que se proyecta como contenido.
 */
@Component({
  selector: 'app-empty-state',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (icon()) {
      <mat-icon [svgIcon]="icon()!" aria-hidden="true" />
    }
    <h3>{{ title() }}</h3>
    @if (message()) {
      <p>{{ message() }}</p>
    }
    <div class="actions"><ng-content /></div>
  `,
  styles: `
    :host {
      display: grid;
      justify-items: center;
      gap: var(--ov-space-2);
      padding: var(--ov-space-7) var(--ov-space-4);
      text-align: center;
    }

    mat-icon {
      width: 2.5rem;
      height: 2.5rem;
      margin-bottom: var(--ov-space-2);
      color: var(--ov-ink-muted);
    }

    h3 {
      font-size: var(--ov-text-lg);
    }

    p {
      max-width: var(--ov-reading-max);
      color: var(--ov-ink-muted);
    }

    .actions:not(:empty) {
      margin-top: var(--ov-space-3);
    }
  `,
})
export class EmptyStateComponent {
  readonly title = input.required<string>();
  readonly message = input<string>();
  readonly icon = input<string>();
}

/**
 * Error al obtener datos. Muestra el motivo que explicó el backend y ofrece
 * reintentar; no se disculpa ni es vago sobre lo que pasó. En la vista del comprador va
 * con `tone="neutral"`: sin rojo, el ícono en tinta.
 */
@Component({
  selector: 'app-error-state',
  imports: [MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-icon svgIcon="error" aria-hidden="true" />
    <p class="message">{{ message() }}</p>
    @if (retryable()) {
      <button mat-stroked-button type="button" (click)="retry.emit()">
        <mat-icon svgIcon="refresh" />
        Reintentar
      </button>
    }
  `,
  host: { role: 'alert', '[class.neutral]': "tone() === 'neutral'" },
  styles: `
    :host {
      display: grid;
      justify-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-7) var(--ov-space-4);
      text-align: center;
    }

    mat-icon[svgIcon='error'] {
      width: 2.5rem;
      height: 2.5rem;
      color: var(--ov-danger);
    }

    :host(.neutral) mat-icon[svgIcon='error'] {
      color: var(--ov-ink-muted);
    }

    .message {
      max-width: var(--ov-reading-max);
    }
  `,
})
export class ErrorStateComponent {
  readonly message = input.required<string>();
  readonly retryable = input(true);
  readonly tone = input<'alert' | 'neutral'>('alert');
  readonly retry = output<void>();
}
