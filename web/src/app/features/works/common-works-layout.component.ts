import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { WorkStore } from './work.store';

/**
 * Obras comunes del desarrollo: lo que es de todos y no de una casa, como calles, redes,
 * portal y club house. Tienen etapas, historia y recorridos igual que una casa, y sus
 * novedades llegan a todos los compradores del desarrollo.
 *
 * <p>Las pantallas son las mismas que dentro de una casa: sin casa abierta, trabajan
 * sobre lo común.
 */
@Component({
  selector: 'app-common-works-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page intro-page">
      <div class="intro">
        <div>
          <h2>Obras comunes</h2>
          <p class="ov-lead">
            Calles, redes de servicios, portal, club house: lo que se construye para todo el desarrollo. Lo que publiques
            acá lo ven todos los compradores, junto a la historia de su casa.
          </p>
        </div>
        <nav class="segmented" aria-label="Obras comunes">
          <a [routerLink]="link('etapas')" routerLinkActive="active" ariaCurrentWhenActive="page">Etapas</a>
          <a [routerLink]="link('historia')" routerLinkActive="active" ariaCurrentWhenActive="page">Historia</a>
          <a [routerLink]="link('recorridos')" routerLinkActive="active" ariaCurrentWhenActive="page">Recorridos 3D</a>
        </nav>
      </div>
    </div>
    <router-outlet />
  `,
  styles: `
    .intro-page {
      padding-top: var(--ov-space-6);
      padding-bottom: 0;
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface-muted);
    }

    .ov-lead {
      max-width: var(--ov-reading-max);
      margin-top: var(--ov-space-2);
    }

    .segmented {
      display: inline-flex;
      gap: var(--ov-space-1);
      padding: var(--ov-space-1);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
    }

    .segmented a {
      display: inline-flex;
      align-items: center;
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border-radius: var(--ov-radius-pill);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      text-decoration: none;
    }

    .segmented a.active {
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }
  `,
})
export class CommonWorksLayoutComponent {

  private readonly store = inject(WorkStore);

  protected link(path: string): string[] {
    return [...this.store.basePath(), 'comunes', path];
  }
}
