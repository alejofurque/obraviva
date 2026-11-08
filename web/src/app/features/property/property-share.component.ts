import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CreatedShareLink, ShareLinkSummary } from '../../core/api/api.models';
import { MyPropertyService } from '../../core/api/my-property.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { PropertyViewStore } from './property-view.store';
import { ShortDatePipe } from '../../shared/time/short-date';

type Expiry = 'never' | '7' | '30' | '90';

/**
 * Enlaces públicos de la unidad.
 *
 * <p>El token existe una sola vez, en la respuesta de creación: el backend guarda sólo su
 * hash. Por eso el enlace nuevo se muestra con la advertencia visible; ocultarlo o dar a
 * entender que se puede recuperar sería mentirle a quien lo genera.
 */
@Component({
  selector: 'app-property-share',
  imports: [ShortDatePipe, ReactiveFormsModule, MatIconModule, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow share">
      <header class="intro">
        <h2 class="ov-display">{{ fromWork ? 'Mostrá esta propiedad' : 'Compartí tu propiedad' }}</h2>
        <p class="ov-lead">
          El enlace muestra el avance, la historia, el recorrido y los planos. Nunca precios,
          contratos ni datos personales. Lo podés cortar cuando quieras.
          @if (fromWork) { Sirve para mostrarla a un interesado o mandársela a quien la compró. }
        </p>
      </header>

      <section class="ov-card panel" aria-labelledby="new-title">
        <h3 id="new-title">Nuevo enlace</h3>
        <form [formGroup]="form" (ngSubmit)="create()" class="form">
          <div class="ov-field">
            <label class="ov-eyebrow" for="label">Para quién es</label>
            <input id="label" class="ov-input" formControlName="label" maxlength="120"
                   [placeholder]="fromWork ? 'Por ejemplo: interesado en la 3B, Juan Pérez' : 'Por ejemplo: familia, arquitecta'" />
          </div>
          <div class="ov-field">
            <label class="ov-eyebrow" for="expiry">Vence</label>
            <select id="expiry" class="ov-input" formControlName="expiry">
              <option value="never">No vence</option>
              <option value="7">En 7 días</option>
              <option value="30">En 30 días</option>
              <option value="90">En 90 días</option>
            </select>
          </div>
          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="creating()">
            {{ creating() ? 'Generando…' : 'Generar enlace' }}
          </button>
        </form>

        @if (formError(); as message) {
          <p class="form-error" role="alert"><mat-icon svgIcon="error" aria-hidden="true" /><span>{{ message }}</span></p>
        }

        @if (created(); as link) {
          <div class="created" role="status">
            <p class="warning">
              Copiá el enlace ahora. Por seguridad no se guarda, y no lo vamos a poder mostrar de nuevo.
            </p>
            <div class="copy-row">
              <input class="ov-input" [value]="shareUrl(link)" readonly aria-label="Enlace para compartir"
                     (focus)="$any($event.target).select()" />
              <button class="ov-btn ov-btn--secondary" type="button" (click)="copy(link)">
                {{ copied() ? 'Copiado' : 'Copiar' }}
              </button>
            </div>
          </div>
        }
      </section>

      <section class="links" aria-labelledby="links-title">
        <h3 id="links-title">Enlaces generados</h3>

        @if (loading()) {
          <app-loading-state label="Cargando enlaces…" />
        } @else if (listError(); as message) {
          <app-error-state tone="neutral" [message]="message" (retry)="loadLinks()" />
        } @else if (links().length === 0) {
          <app-empty-state title="Todavía no compartiste esta propiedad" />
        } @else {
          <ul class="link-list">
            @for (link of links(); track link.id) {
              <li class="link">
                <div class="link-main">
                  <p class="link-label">{{ link.label || 'Sin nombre' }}</p>
                  <p class="link-meta">
                    Creado el {{ link.createdAt | shortDate: 'd MMM y' }}
                    @if (link.expiresAt) { · vence el {{ link.expiresAt | shortDate: 'd MMM y' }} }
                  </p>
                  <p class="link-meta ov-num">
                    {{ link.accessCount === 1 ? '1 visita' : link.accessCount + ' visitas' }}
                    @if (link.lastAccessedAt) { · última el {{ link.lastAccessedAt | shortDate: 'd MMM y, HH:mm' }} }
                  </p>
                </div>
                <span class="ov-pill" [class.ov-pill--accent]="statusOf(link) === 'Activo'">{{ statusOf(link) }}</span>
                @if (link.active) {
                  @if (confirming() === link.id) {
                    <div class="confirm">
                      <button class="ov-btn ov-btn--primary" type="button" (click)="revoke(link)">Cortar enlace</button>
                      <button class="ov-link" type="button" (click)="confirming.set(null)">Cancelar</button>
                    </div>
                  } @else {
                    <button class="ov-btn ov-btn--secondary" type="button" (click)="confirming.set(link.id)">Revocar</button>
                  }
                }
              </li>
            }
          </ul>
        }
      </section>
    </div>
  `,
  styles: `
    .share {
      display: grid;
      gap: var(--ov-space-7);
      --ov-measure: 52rem;
    }

    .intro {
      display: grid;
      gap: var(--ov-space-3);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
      align-items: end;
    }

    /* Vista del comprador: un error se dice con ícono y texto, sin rojo. */
    .form-error {
      display: flex;
      align-items: flex-start;
      gap: var(--ov-space-2);
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
      line-height: 1.5;
    }

    .form-error mat-icon {
      flex: none;
      width: 1.25rem;
      height: 1.25rem;
      color: var(--ov-ink-muted);
    }

    .created {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
    }

    .warning {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .copy-row {
      display: flex;
      gap: var(--ov-space-2);
    }

    .links h3 {
      margin-bottom: var(--ov-space-4);
    }

    .link-list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .link {
      display: grid;
      grid-template-columns: 1fr auto;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) 0;
      border-top: 1px solid var(--ov-line);
    }

    .link-label {
      font-weight: var(--ov-weight-medium);
    }

    .link-meta {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .confirm,
    .link > .ov-btn {
      grid-column: 1 / -1;
      justify-self: start;
    }

    .confirm {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    @media (min-width: 48rem) {
      .form {
        grid-template-columns: 1fr 12rem auto;
      }

      .link {
        grid-template-columns: 1fr auto auto;
      }

      .confirm,
      .link > .ov-btn {
        grid-column: auto;
      }
    }
  `,
})
export class PropertyShareComponent implements OnInit {

  private readonly store = inject(PropertyViewStore);
  /** Abierto desde la obra por el dueño, para mostrar la unidad a interesados. */
  protected readonly fromWork = inject(ActivatedRoute).snapshot.queryParamMap.has('obra');
  private readonly myProperty = inject(MyPropertyService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly links = signal<ShareLinkSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly listError = signal<string | null>(null);
  protected readonly created = signal<CreatedShareLink | null>(null);
  protected readonly creating = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly copied = signal(false);
  protected readonly confirming = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    label: ['', Validators.maxLength(120)],
    expiry: ['never' as Expiry],
  });

  private readonly unitId = computed(() => {
    const source = this.store.source();
    return source?.kind === 'private' ? source.unitId : '';
  });

  ngOnInit(): void {
    this.loadLinks();
  }

  protected loadLinks(): void {
    this.loading.set(true);
    this.listError.set(null);
    this.myProperty.listShareLinks(this.unitId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: links => {
          this.links.set(links);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.listError.set(errorMessageOf(error, 'No se pudieron cargar los enlaces.'));
        },
      });
  }

  protected create(): void {
    this.creating.set(true);
    this.formError.set(null);
    this.copied.set(false);

    const { label, expiry } = this.form.getRawValue();
    const payload: { label?: string; expiresAt?: string } = {};
    if (label.trim()) {
      payload.label = label.trim();
    }
    if (expiry !== 'never') {
      payload.expiresAt = new Date(Date.now() + Number(expiry) * 24 * 60 * 60 * 1000).toISOString();
    }

    this.myProperty.createShareLink(this.unitId(), payload).subscribe({
      next: link => {
        this.creating.set(false);
        this.created.set(link);
        this.form.reset({ label: '', expiry: 'never' });
        this.loadLinks();
      },
      error: (error: unknown) => {
        this.creating.set(false);
        this.formError.set(errorMessageOf(error, 'No se pudo generar el enlace. Probá de nuevo en un momento.'));
      },
    });
  }

  /** Lo que se comparte es la dirección del frontend, no la de la API. */
  protected shareUrl(link: CreatedShareLink): string {
    return `${window.location.origin}/p/${link.token}`;
  }

  protected copy(link: CreatedShareLink): void {
    // El portapapeles sólo funciona en contextos seguros: localhost sí, una IP de la red no.
    navigator.clipboard?.writeText(this.shareUrl(link)).then(
      () => this.copied.set(true),
      () => this.formError.set('No se pudo copiar. Seleccioná el enlace y copialo a mano.'),
    ) ?? this.formError.set('Este navegador no permite copiar desde acá. Seleccioná el enlace y copialo a mano.');
  }

  protected revoke(link: ShareLinkSummary): void {
    this.confirming.set(null);
    this.myProperty.revokeShareLink(link.id).subscribe({
      next: () => this.loadLinks(),
      error: (error: unknown) => this.listError.set(errorMessageOf(error, 'No se pudo revocar el enlace.')),
    });
  }

  protected statusOf(link: ShareLinkSummary): 'Activo' | 'Revocado' | 'Vencido' {
    if (link.revokedAt) {
      return 'Revocado';
    }
    return link.active ? 'Activo' : 'Vencido';
  }
}
