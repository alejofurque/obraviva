import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { of, switchMap } from 'rxjs';
import { RETURN_PARAM, entryTree } from '../../core/auth/auth.guard';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/session/user-context.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { BrandComponent } from '../../shared/ui/brand.component';
import { loadDisplayFont } from '../../shared/ui/display-font';
import { LoginShowcaseComponent } from './login-showcase.component';

/**
 * Ingreso. A la izquierda el formulario; a la derecha, en el verde
 * de obra, tres tarjetas decorativas de cómo se ve una obra y "Ver. Seguir. Mostrar.". En el
 * teléfono el verde es una franja arriba, con dos tarjetas en chico, y el formulario va abajo.
 *
 * <p>"Entrar" queda tenue hasta que hay correo y contraseña; la contraseña se puede mostrar
 * dentro del mismo campo. El título va en Fraunces, que se carga local sólo en esta pantalla y
 * en la vista del comprador.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, BrandComponent, LoginShowcaseComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="login">
      <section class="form-side">
        <app-brand class="brand" />

        <div class="content">
          <h1 class="ov-display">Tu obra,<br />más cerca.</h1>
          <p class="ov-lead intro">Ingresá para ver cómo avanza.</p>

          @if (auth.restoreProblem(); as problem) {
            <div class="restore" role="status">
              <p>{{ problem }}</p>
              <button type="button" class="ov-btn ov-btn--secondary" [disabled]="restoring()" (click)="retryRestore()">
                {{ restoring() ? 'Reintentando…' : 'Reintentar' }}
              </button>
            </div>
          }

          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <div class="ov-field">
              <label class="ov-eyebrow" for="email">Correo electrónico</label>
              <input
                id="email"
                class="ov-input"
                type="email"
                formControlName="email"
                autocomplete="email"
                inputmode="email"
                placeholder="nombre@correo.com"
                [attr.aria-invalid]="errorMessage() ? true : null"
              />
            </div>

            <div class="ov-field">
              <label class="ov-eyebrow" for="password">Contraseña</label>
              <div class="password">
                <input
                  id="password"
                  class="ov-input"
                  [type]="showPassword() ? 'text' : 'password'"
                  formControlName="password"
                  autocomplete="current-password"
                  [attr.aria-invalid]="errorMessage() ? true : null"
                />
                <button
                  type="button"
                  class="reveal"
                  aria-controls="password"
                  [attr.aria-pressed]="showPassword()"
                  [attr.aria-label]="showPassword() ? 'Ocultar la contraseña' : 'Mostrar la contraseña'"
                  (click)="showPassword.set(!showPassword())"
                >
                  {{ showPassword() ? 'Ocultar' : 'Mostrar' }}
                </button>
              </div>
            </div>

            @if (errorMessage(); as message) {
              <p class="ov-error" role="alert">{{ message }}</p>
            }

            <button
              class="ov-btn ov-btn--primary ov-btn--block submit"
              type="submit"
              [disabled]="form.invalid || loading()"
            >
              {{ loading() ? 'Entrando…' : 'Entrar' }}
            </button>
          </form>

          <p class="help">
            ¿No podés entrar? Pedile una contraseña nueva a quien administra tu obra.
          </p>
        </div>

        <p class="footer">ObraViva · Experiencia digital para propiedades en construcción</p>
      </section>

      <aside class="brand-side" aria-label="Así se ve una obra en ObraViva">
        <app-brand class="band-brand" [inverse]="true" />
        <app-login-showcase class="showcase" />
        <div class="promises-block">
          <p class="promise-title">Ver. Seguir. Mostrar.</p>
          <dl class="promises">
            <div>
              <dt>Ver</dt>
              <dd>Recorré la obra en 3D desde el navegador, como si caminaras por adentro.</dd>
            </div>
            <div>
              <dt>Seguir</dt>
              <dd>La historia de la construcción, con fotos, etapas y estados.</dd>
            </div>
            <div>
              <dt>Mostrar</dt>
              <dd>Compartí tu propiedad con un enlace seguro, sin exponer nada privado.</dd>
            </div>
          </dl>
        </div>
      </aside>
    </div>
  `,
  styles: `
    .login {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      min-height: 100dvh;
    }

    .form-side {
      display: grid;
      grid-template-rows: auto 1fr auto;
      padding: var(--ov-space-6) var(--ov-gutter);
    }

    .content {
      align-self: center;
      width: 100%;
      max-width: 25rem;
      margin: var(--ov-space-7) auto;
    }

    h1 {
      font-size: min(var(--ov-text-display), 4rem);
    }

    .intro {
      margin: var(--ov-space-4) 0 var(--ov-space-7);
    }

    .restore {
      display: grid;
      gap: var(--ov-space-3);
      margin-bottom: var(--ov-space-5);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
    }

    .restore p {
      margin: 0;
    }

    form {
      display: grid;
      gap: var(--ov-space-5);
    }

    /* Campos de 52 px y con radio, como en el boceto: más cómodos para el pulgar. */
    .ov-input {
      min-height: 3.25rem;
      border-radius: var(--ov-radius-md);
    }

    .password {
      position: relative;
    }

    .password .ov-input {
      padding-right: 6rem;
    }

    .reveal {
      position: absolute;
      top: 0.375rem;
      right: 0.375rem;
      height: 2.5rem;
      padding: 0 var(--ov-space-3);
      border: 0;
      border-radius: var(--ov-radius-sm);
      background: transparent;
      color: var(--ov-accent);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      cursor: pointer;
    }

    .reveal:hover {
      background: var(--ov-accent-soft);
    }

    .submit {
      margin-top: var(--ov-space-2);
    }

    /* Deshabilitado: el mismo botón, tenue (no el gris plano de la opacidad), hasta que haya
       correo y contraseña. */
    .submit:disabled {
      opacity: 1;
      background: color-mix(in srgb, var(--ov-ink) 62%, var(--ov-canvas));
      color: color-mix(in srgb, var(--ov-on-ink) 72%, transparent);
    }

    .help {
      margin-top: var(--ov-space-5);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .footer {
      margin: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    /* Teléfono: el verde es una franja arriba, con la marca, dos tarjetas y la promesa. */
    .brand-side {
      position: relative;
      order: -1;
      height: 17rem;
      overflow: hidden;
      background: var(--ov-accent);
      color: var(--ov-on-ink);
    }

    .band-brand {
      position: absolute;
      top: var(--ov-space-5);
      left: var(--ov-gutter);
      z-index: 1;
    }

    .showcase {
      position: absolute;
      inset: 0;
    }

    .promises-block {
      position: absolute;
      right: var(--ov-gutter);
      bottom: var(--ov-space-5);
      left: var(--ov-gutter);
    }

    .promise-title {
      margin: 0;
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      line-height: var(--ov-leading-tight);
      letter-spacing: -0.02em;
    }

    .promises,
    .form-side .brand {
      display: none;
    }

    @media (min-width: 60rem) {
      .login {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      }

      .form-side {
        padding: var(--ov-space-6) var(--ov-space-8);
      }

      .form-side .brand {
        display: inline-flex;
      }

      .brand-side {
        display: flex;
        flex-direction: column;
        order: 0;
        height: auto;
        min-height: 100dvh;
      }

      .band-brand {
        display: none;
      }

      .showcase {
        position: relative;
        flex: 1;
      }

      .promises-block {
        position: static;
        display: grid;
        gap: var(--ov-space-6);
        padding: 0 var(--ov-space-8) var(--ov-space-8);
      }

      .promise-title {
        font-size: var(--ov-text-display);
        letter-spacing: var(--ov-tracking-display);
      }

      .promises {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: var(--ov-space-5);
        margin: 0;
      }

      .promises > div {
        padding-top: var(--ov-space-3);
        border-top: 1px solid color-mix(in srgb, var(--ov-on-ink) 35%, transparent);
      }

      dt {
        font-weight: var(--ov-weight-semibold);
      }

      dd {
        margin: var(--ov-space-1) 0 0;
        font-size: var(--ov-text-sm);
        opacity: 0.85;
      }
    }
  `,
})
export class LoginComponent {

  private readonly formBuilder = inject(FormBuilder);
  protected readonly auth = inject(AuthService);
  private readonly context = inject(UserContextService);
  private readonly router = inject(Router);
  /** La dirección a la que iba la persona antes de que le pidieran entrar. */
  private readonly returnUrl = inject(ActivatedRoute).snapshot.queryParamMap.get(RETURN_PARAM);

  protected readonly restoring = signal(false);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly showPassword = signal(false);

  readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  constructor() {
    loadDisplayFont(inject(DOCUMENT));
  }

  /** La sesión guardada no se pudo recuperar porque el servidor no respondía: otra vez. */
  protected retryRestore(): void {
    this.restoring.set(true);
    this.auth.restoreSession()
      .pipe(switchMap(result => result === 'restored' ? entryTree(this.router, this.context, this.returnUrl) : of(null)))
      .subscribe(target => {
        this.restoring.set(false);
        if (target) {
          void this.router.navigateByUrl(target, { replaceUrl: true });
        }
      });
  }

  submit(): void {
    if (this.form.invalid) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    const { email, password } = this.form.getRawValue();
    this.auth
      .login({ email: email.trim(), password })
      .pipe(switchMap(() => entryTree(this.router, this.context, this.returnUrl)))
      .subscribe({
        // El ingreso no queda en el historial: "Volver" y el atrás no regresan a él.
        next: target => this.router.navigateByUrl(target, { replaceUrl: true }),
        error: (error: unknown) => {
          this.loading.set(false);
          this.errorMessage.set(
            error instanceof HttpErrorResponse && error.status === 401
              ? 'Correo o contraseña incorrectos.'
              : errorMessageOf(error, 'No se pudo entrar. Volvé a intentar.'),
          );
        },
      });
  }
}
