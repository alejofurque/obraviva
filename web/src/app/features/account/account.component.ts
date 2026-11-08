import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessageOf } from '../../shared/http/error-message';

const MIN_PASSWORD_LENGTH = 8;

/** La confirmación tiene que coincidir con la nueva: un error de tipeo deja a la persona afuera. */
function matchingPasswords(group: AbstractControl): ValidationErrors | null {
  const next = group.get('newPassword')?.value;
  const confirmation = group.get('confirmation')?.value;
  return next && confirmation && next !== confirmation ? { mismatch: true } : null;
}

/** Datos de la cuenta y cambio de contraseña. */
@Component({
  selector: 'app-account',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow narrow">
      <p class="ov-eyebrow">Mi cuenta</p>
      <h1 class="title">{{ auth.currentUser()?.fullName }}</h1>
      <p class="ov-lead">{{ auth.currentUser()?.email }}</p>

      <section class="ov-card panel" aria-labelledby="password-title">
        <h2 id="password-title">Cambiar contraseña</h2>
        <p class="ov-lead hint">
          Al cambiarla se cierran tus sesiones en otros dispositivos. En este seguís adentro.
        </p>

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="ov-field">
            <label class="ov-eyebrow" for="current">Contraseña actual</label>
            <input id="current" class="ov-input" type="password" formControlName="currentPassword"
                   autocomplete="current-password" />
          </div>

          <div class="ov-field">
            <label class="ov-eyebrow" for="next">Contraseña nueva</label>
            <input id="next" class="ov-input" type="password" formControlName="newPassword"
                   autocomplete="new-password" aria-describedby="next-hint" />
            <p id="next-hint" class="field-hint">Al menos {{ minLength }} caracteres.</p>
          </div>

          <div class="ov-field">
            <label class="ov-eyebrow" for="confirmation">Repetí la contraseña nueva</label>
            <input id="confirmation" class="ov-input" type="password" formControlName="confirmation"
                   autocomplete="new-password"
                   [attr.aria-invalid]="form.hasError('mismatch') ? true : null" />
            @if (form.hasError('mismatch')) {
              <p class="ov-error">Las contraseñas no coinciden.</p>
            }
          </div>

          @if (errorMessage(); as message) {
            <p class="ov-error" role="alert">{{ message }}</p>
          }
          @if (saved()) {
            <p class="saved" role="status">Contraseña actualizada.</p>
          }

          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="form.invalid || saving()">
            {{ saving() ? 'Guardando…' : 'Cambiar contraseña' }}
          </button>
        </form>
      </section>
    </div>
  `,
  styles: `
    .narrow {
      --ov-measure: var(--ov-reading-max);
    }

    .title {
      margin: var(--ov-space-3) 0 var(--ov-space-1);
    }

    .panel {
      margin-top: var(--ov-space-7);
      padding: var(--ov-space-6);
    }

    .hint {
      margin: var(--ov-space-2) 0 var(--ov-space-5);
      font-size: var(--ov-text-sm);
    }

    form {
      display: grid;
      gap: var(--ov-space-5);
      justify-items: start;
    }

    .ov-field {
      width: 100%;
    }

    .field-hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .saved {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class AccountComponent {

  protected readonly auth = inject(AuthService);
  private readonly formBuilder = inject(FormBuilder);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(MIN_PASSWORD_LENGTH)]],
      confirmation: ['', Validators.required],
    },
    { validators: matchingPasswords },
  );

  protected submit(): void {
    if (this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.errorMessage.set(null);

    const { currentPassword, newPassword } = this.form.getRawValue();
    this.auth.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.reset();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.errorMessage.set(errorMessageOf(error, 'No se pudo cambiar la contraseña.'));
      },
    });
  }
}
