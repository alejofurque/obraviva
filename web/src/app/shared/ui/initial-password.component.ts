import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

/**
 * Contraseña inicial de una cuenta recién creada o reiniciada.
 *
 * <p>Se muestra una sola vez, porque el backend guarda sólo su hash: la advertencia es
 * visible y no se puede cerrar sin decidirlo. Ocultarlo sería mentirle a quien la tiene
 * que comunicar.
 */
@Component({
  selector: 'app-initial-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="box" role="status">
      <p class="lead">
        <strong>{{ heading() }}</strong>
        Pasásela a {{ person() }} por un medio seguro. Por seguridad no se guarda y no la vamos a poder mostrar de nuevo.
      </p>
      <div class="row">
        <code class="password ov-num">{{ password() }}</code>
        <button class="ov-btn ov-btn--secondary" type="button" (click)="copy()">{{ copied() ? 'Copiada' : 'Copiar' }}</button>
        <button class="ov-link" type="button" (click)="dismissed.emit()">Ya la anoté</button>
      </div>
      @if (copyError()) {
        <p class="ov-error">No se pudo copiar. Seleccioná la contraseña y copiala a mano.</p>
      }
    </div>
  `,
  styles: `
    .box {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
    }

    .lead {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
    }

    .lead strong {
      display: block;
      margin-bottom: var(--ov-space-1);
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .password {
      padding: var(--ov-space-2) var(--ov-space-4);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      font-size: var(--ov-text-lg);
      letter-spacing: 0.06em;
      user-select: all;
    }
  `,
})
export class InitialPasswordComponent {
  readonly password = input.required<string>();
  readonly person = input('la persona');
  readonly heading = input('Contraseña inicial');
  readonly dismissed = output<void>();

  protected readonly copied = signal(false);
  protected readonly copyError = signal(false);

  protected copy(): void {
    const clipboard = navigator.clipboard;
    if (!clipboard) {
      this.copyError.set(true);
      return;
    }
    clipboard.writeText(this.password()).then(
      () => this.copied.set(true),
      () => this.copyError.set(true),
    );
  }
}
