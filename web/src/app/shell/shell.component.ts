import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { OwnerNavStore } from '../core/session/owner-nav.store';
import { UserContextService } from '../core/session/user-context.service';
import { BrandComponent } from '../shared/ui/brand.component';
import { AccountMenuComponent, mainNavItems, signOut } from './account-menu.component';
import { OwnerSidebarComponent } from './owner-sidebar.component';

/**
 * Marco de la aplicación autenticada: marca, navegación y cuenta.
 *
 * <p>La navegación sale de lo que la persona tiene, no de un rol: quien administra obras
 * ve Obras, quien compró ve su propiedad, y quien tiene las dos cosas ve ambas.
 *
 * <p>Quien es dueño de alguna obra trabaja con el menú lateral (`OwnerNavStore.active`): en
 * el teléfono se pliega detrás de un botón y se abre como panel. "Hoy" y las pantallas del
 * comprador conservan la barra de arriba, igual que quien sólo es encargado o comprador.
 *
 * <p>El trabajo de campo (`/hoy`) trae su propio marco, `FieldLayoutComponent`: barra abajo en
 * el teléfono y, en escritorio, una barra con sus secciones y el selector de casa. Ahí esta
 * barra no se dibuja, para no apilar dos.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, BrandComponent, OwnerSidebarComponent, AccountMenuComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (nav.active()) {
      <div class="owner">
        <aside class="side" aria-label="Menú">
          <app-owner-sidebar (logout)="logout()" />
        </aside>
        <header class="mobile-bar">
          <a [routerLink]="context.homeUrl()" class="brand-link ov-hit" aria-label="Ir al inicio"><app-brand /></a>
          <button type="button" class="menu-button" aria-label="Abrir el menú" aria-controls="owner-drawer"
                  [attr.aria-expanded]="drawerOpen()" (click)="openDrawer()">
            <mat-icon svgIcon="menu" aria-hidden="true" />
          </button>
        </header>
        <dialog #drawer id="owner-drawer" class="drawer" aria-label="Menú" (close)="drawerOpen.set(false)"
                (click)="closeOnBackdrop($event)">
          <button type="button" class="drawer-close" aria-label="Cerrar el menú" (click)="closeDrawer()">
            <mat-icon svgIcon="close" aria-hidden="true" />
          </button>
          <app-owner-sidebar (navigated)="closeDrawer()" (logout)="logout()" />
        </dialog>
        <main class="owner-main">
          <router-outlet />
        </main>
      </div>
    } @else {
    @if (!ownBarRoute()) {
    <header class="header">
      <div class="bar">
        <a [routerLink]="context.homeUrl()" class="brand-link ov-hit" aria-label="Ir al inicio"><app-brand /></a>

        <nav class="nav" aria-label="Principal">
          @for (item of navItems(); track item.label) {
            <a
              [routerLink]="item.link"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
            >{{ item.label }}</a>
          }
        </nav>

        <app-account-menu class="account" navInMenu="phone" />
      </div>
    </header>
    }

    <main>
      <router-outlet />
    </main>
    }
  `,
  styles: `
    :host {
      display: block;
      min-height: 100dvh;
    }

    .header {
      position: sticky;
      top: 0;
      z-index: 10;
      border-bottom: 1px solid var(--ov-line);
      background: color-mix(in srgb, var(--ov-canvas) 86%, transparent);
      backdrop-filter: blur(12px);
    }

    .bar {
      display: grid;
      grid-template-columns: 1fr auto 1fr;
      align-items: center;
      max-width: var(--ov-content-max);
      height: var(--ov-header-height);
      margin: 0 auto;
      padding: 0 var(--ov-gutter);
    }

    .brand-link {
      justify-self: start;
      text-decoration: none;
    }

    .nav {
      display: none;
      gap: var(--ov-space-6);
    }

    .nav a {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
      transition: color var(--ov-duration-fast) var(--ov-ease);
    }

    .nav a:hover,
    .nav a.active {
      color: var(--ov-ink);
    }

    .account {
      /* Columna fija: en el teléfono las secciones no se ven y no ocupan la del medio. */
      grid-column: 3;
      justify-self: end;
    }

    /* --- Marco del dueño --- */
    .owner {
      display: grid;
      grid-template-columns: 16rem minmax(0, 1fr);
      min-height: 100dvh;
    }

    .side {
      position: sticky;
      top: 0;
      height: 100dvh;
      border-right: 1px solid var(--ov-line);
    }

    /* El contenido usa el ancho al lado del menú, con un máximo para pantallas grandes. */
    .owner-main {
      --ov-content-max: 96rem;
      min-width: 0;
    }

    .mobile-bar {
      display: none;
    }

    .drawer {
      width: min(18rem, 86vw);
      height: 100dvh;
      max-height: none;
      margin: 0;
      padding: 0;
      border: 0;
      background: var(--ov-surface);
    }

    .drawer::backdrop {
      background: color-mix(in srgb, var(--ov-ink) 40%, transparent);
    }

    .drawer-close {
      position: absolute;
      top: var(--ov-space-3);
      right: var(--ov-space-3);
      z-index: 1;
      display: grid;
      place-items: center;
      width: 2.5rem;
      height: 2.5rem;
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink);
      cursor: pointer;
    }

    .menu-button {
      display: grid;
      place-items: center;
      width: 2.75rem;
      height: 2.75rem;
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink);
      cursor: pointer;
    }

    .menu-button:focus-visible,
    .drawer-close:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    @media (max-width: 63.99rem) {
      .owner {
        grid-template-columns: minmax(0, 1fr);
        grid-template-rows: auto 1fr;
      }

      .side {
        display: none;
      }

      .mobile-bar {
        position: sticky;
        top: 0;
        z-index: 10;
        display: flex;
        align-items: center;
        justify-content: space-between;
        height: var(--ov-header-height);
        padding: 0 var(--ov-gutter);
        border-bottom: 1px solid var(--ov-line);
        background: color-mix(in srgb, var(--ov-canvas) 86%, transparent);
        backdrop-filter: blur(12px);
      }
    }

    @media (min-width: 48rem) {
      .nav {
        display: flex;
      }

    }
  `,
})
export class ShellComponent implements OnInit {

  private readonly auth = inject(AuthService);
  protected readonly context = inject(UserContextService);
  protected readonly nav = inject(OwnerNavStore);
  private readonly drawer = viewChild<ElementRef<HTMLDialogElement>>('drawer');
  protected readonly drawerOpen = signal(false);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * En el trabajo de campo y en la vista de una propiedad, la barra de arriba es la suya: una
   * sola barra, no dos apiladas.
   */
  protected readonly ownBarRoute = toSignal(
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), map(() => hasOwnBar(this.router.url))),
    { initialValue: hasOwnBar(this.router.url) },
  );

  protected readonly navItems = computed(() => mainNavItems(this.context));

  ngOnInit(): void {
    // Entrada directa a una página del marco: el contexto puede no estar cargado.
    this.context.ensureLoaded().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => {} });
    // Cualquier cambio de pantalla cierra el menú del teléfono: un destino del menú, un enlace
    // de la página o el atrás del navegador.
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.closeDrawer());
  }

  protected openDrawer(): void {
    const drawer = this.drawer()?.nativeElement;
    if (drawer && typeof drawer.showModal === 'function') {
      drawer.showModal();
      this.drawerOpen.set(true);
    }
  }

  protected closeDrawer(): void {
    this.drawer()?.nativeElement.close?.();
    this.drawerOpen.set(false);
  }

  /** Un toque fuera del panel (en el fondo del diálogo) lo cierra. */
  protected closeOnBackdrop(event: MouseEvent): void {
    if (event.target === this.drawer()?.nativeElement) {
      this.closeDrawer();
    }
  }

  protected logout(): void {
    this.closeDrawer();
    signOut(this.auth, this.router);
  }
}

function isFieldUrl(url: string): boolean {
  return url === '/hoy' || url.startsWith('/hoy/') || url.startsWith('/hoy?');
}

/** El campo y una propiedad (`/mi-propiedad/:unitId`) dibujan su propia barra. */
export function hasOwnBar(url: string): boolean {
  return isFieldUrl(url) || /^\/mi-propiedad\/[^/?#]+/.test(url);
}
