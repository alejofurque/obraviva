import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ShareLinkSummary } from '../../core/api/api.models';
import { MyPropertyService } from '../../core/api/my-property.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { todayLocal } from '../../shared/time/local-date';
import { shortDay } from '../property/buyer-derivations';
import { localDay } from './activity-derivations';
import { relativeWhen } from './overview-derivations';

/** "Activo", "Cortado" o "Vencido": un enlace que no se cortó pero ya no abre, venció. */
export function linkState(link: Pick<ShareLinkSummary, 'active' | 'revokedAt'>): 'Activo' | 'Cortado' | 'Vencido' {
  if (link.revokedAt) return 'Cortado';
  return link.active ? 'Activo' : 'Vencido';
}

/**
 * La línea de un enlace para el dueño: quién lo creó, cuándo, si vence y su último acceso;
 * cortado o vencido, sólo cuándo. "Creado por vos" si lo creó quien mira.
 */
export function linkMeta(link: ShareLinkSummary, today: string = todayLocal()): string {
  if (link.revokedAt) {
    return `Cortado el ${shortDay(localDay(link.revokedAt))}`;
  }
  if (!link.active && link.expiresAt) {
    return `Venció el ${shortDay(localDay(link.expiresAt))}`;
  }
  const parts: string[] = [];
  const author = link.createdByYou ? 'vos' : link.createdByName;
  parts.push(author ? `Creado por ${author} · ${shortDay(localDay(link.createdAt))}` : `Creado el ${shortDay(localDay(link.createdAt))}`);
  if (link.expiresAt) {
    parts.push(`vence el ${shortDay(localDay(link.expiresAt))}`);
  }
  parts.push(link.lastAccessedAt ? `último acceso ${relativeWhen(link.lastAccessedAt, today)}` : 'sin visitas todavía');
  return parts.join(' · ');
}

/**
 * "Enlaces públicos" de la casa, en Venta: los que crearon el dueño y el comprador, con
 * quién, vencimiento y último acceso, y "Cortar". Crear uno lleva a Compartir, que muestra el
 * enlace una sola vez con su advertencia: no se duplica acá.
 */
@Component({
  selector: 'app-public-links',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card block" aria-labelledby="links-title">
      <div class="head">
        <h3 id="links-title">Enlaces públicos</h3>
        <a class="ov-btn ov-btn--secondary" [routerLink]="['/mi-propiedad', unitId(), 'compartir']" [queryParams]="{ obra: developmentId() }">
          Crear enlace
        </a>
      </div>
      <p class="muted">Para mostrar la casa sin cuenta. Sin precio ni datos personales. Se pueden cortar en cualquier momento.</p>

      @if (error(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }
      @if (links().length === 0 && loaded()) {
        <p class="muted">Todavía no hay enlaces.</p>
      }
      <ul class="list">
        @for (link of links(); track link.id) {
          <li class="link">
            <div class="text">
              <p class="label">
                {{ link.label || 'Sin nombre' }}
                <span class="chip" [class.on]="state(link) === 'Activo'">{{ state(link) }}</span>
              </p>
              <p class="muted">{{ meta(link) }}</p>
            </div>
            @if (link.active) {
              @if (confirming() === link.id) {
                <span class="confirm">
                  <button class="ov-btn ov-btn--primary" type="button" (click)="revoke(link)">Cortar enlace</button>
                  <button class="ov-link" type="button" (click)="confirming.set(null)">Cancelar</button>
                </span>
              } @else {
                <button class="ov-link" type="button" (click)="confirming.set(link.id)">Cortar</button>
              }
            }
          </li>
        }
      </ul>
    </section>
  `,
  styles: `
    .block {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .link {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .text {
      flex: 1 1 14rem;
      min-width: 0;
    }

    .label {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2);
      font-weight: var(--ov-weight-semibold);
    }

    .chip {
      padding: 0.1rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .chip.on {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .confirm {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
    }
  `,
})
export class PublicLinksComponent implements OnInit {
  readonly unitId = input.required<string>();
  readonly developmentId = input.required<string>();

  private readonly myProperty = inject(MyPropertyService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly links = signal<ShareLinkSummary[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);
  protected readonly state = linkState;
  protected readonly meta = (link: ShareLinkSummary) => linkMeta(link);

  ngOnInit(): void {
    this.load();
  }

  protected revoke(link: ShareLinkSummary): void {
    this.confirming.set(null);
    this.error.set(null);
    this.myProperty.revokeShareLink(link.id).subscribe({
      next: () => this.load(),
      error: (error: unknown) => this.error.set(errorMessageOf(error, 'No se pudo cortar el enlace.')),
    });
  }

  private load(): void {
    this.myProperty.listShareLinks(this.unitId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: links => {
        // Los que abren primero; dentro de cada grupo, del más nuevo al más viejo, como llegan.
        this.links.set([...links].sort((x, y) => Number(y.active) - Number(x.active)));
        this.loaded.set(true);
      },
      error: (error: unknown) => {
        this.loaded.set(true);
        this.error.set(errorMessageOf(error, 'No se pudieron cargar los enlaces.'));
      },
    });
  }
}
