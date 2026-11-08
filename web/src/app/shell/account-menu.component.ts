import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatMenuModule } from '@angular/material/menu';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { UserContextService } from '../core/session/user-context.service';

export interface NavItem {
  label: string;
  link: string[];
}

/**
 * Las secciones de la persona, de lo que tiene y no de un rol: quien administra obras ve
 * Desarrollos, quien supervisa ve Hoy, quien compró ve su propiedad.
 */
export function mainNavItems(context: UserContextService): NavItem[] {
  const items: NavItem[] = [];
  const units = context.units();
  if (context.managesDevelopments() && units.length > 0) {
    items.push({ label: 'Inicio', link: ['/inicio'] });
  }
  if (context.supervises()) {
    items.push({ label: 'Hoy', link: ['/hoy'] });
  }
  if (context.managesDevelopments()) {
    items.push({ label: 'Desarrollos', link: ['/desarrollos'] });
  }
  if (units.length === 1) {
    items.push({ label: 'Mi propiedad', link: ['/mi-propiedad', units[0].unitId] });
  } else if (units.length > 1) {
    items.push({ label: 'Mis propiedades', link: ['/mis-propiedades'] });
  }
  return items;
}

/** Cerrar la sesión y volver al ingreso, desde cualquier marco. Los stores se resetean en `AuthService`. */
export function signOut(auth: AuthService, router: Router): void {
  auth.logout().subscribe(() => router.navigate(['/login']));
}

/**
 * El avatar con las iniciales y su menú: quién es, sus secciones, "Mi cuenta" y "Salir". Lo usa
 * la barra de arriba y, en el trabajo de campo del teléfono, el saludo de Hoy, que no tiene
 * barra. `size="large"` lo deja en 48 px, el objetivo táctil del campo.
 */
@Component({
  selector: 'app-account-menu',
  imports: [MatMenuModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="avatar ov-hit" [class.large]="size() === 'large'" [matMenuTriggerFor]="menu"
            [attr.aria-label]="'Cuenta de ' + (user()?.fullName ?? '')">{{ initials() }}</button>

    <mat-menu #menu="matMenu" xPosition="before">
      <div class="identity">
        <strong>{{ user()?.fullName }}</strong>
        <span>{{ user()?.email }}</span>
      </div>
      @for (item of items(); track item.label) {
        <a mat-menu-item class="nav-item" [class.phone-only]="navInMenu() === 'phone'" [routerLink]="item.link">{{ item.label }}</a>
      }
      <a mat-menu-item routerLink="/cuenta">Mi cuenta</a>
      <button mat-menu-item type="button" (click)="logout()">Salir</button>
    </mat-menu>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .avatar {
      display: grid;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .avatar.large {
      width: 3rem;
      height: 3rem;
      font-size: var(--ov-text-md);
    }

    .identity {
      display: grid;
      gap: 2px;
      padding: var(--ov-space-3) var(--ov-space-4);
      border-bottom: 1px solid var(--ov-line);
      font-size: var(--ov-text-sm);
    }

    .identity span {
      color: var(--ov-ink-muted);
    }

    /* Con la barra de arriba a la vista, sus secciones ya están ahí. */
    @media (min-width: 48rem) {
      .nav-item.phone-only {
        display: none;
      }
    }
  `,
})
export class AccountMenuComponent {
  private readonly auth = inject(AuthService);
  private readonly context = inject(UserContextService);
  private readonly router = inject(Router);

  readonly size = input<'regular' | 'large'>('regular');
  /** 'phone': las secciones van en el menú sólo en el teléfono, donde la barra no las muestra. */
  readonly navInMenu = input<'always' | 'phone'>('always');

  protected readonly user = this.auth.currentUser;
  protected readonly items = computed(() => mainNavItems(this.context));
  protected readonly initials = computed(() => {
    const words = (this.user()?.fullName ?? '').trim().split(/\s+/).filter(Boolean);
    return words.slice(0, 2).map(word => word[0].toUpperCase()).join('');
  });

  protected logout(): void {
    signOut(this.auth, this.router);
  }
}
