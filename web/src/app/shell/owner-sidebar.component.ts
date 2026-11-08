import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Development } from '../core/api/api.models';
import { AuthService } from '../core/auth/auth.service';
import { OwnerNavStore } from '../core/session/owner-nav.store';
import { UserContextService } from '../core/session/user-context.service';
import { MENU_HOUSES, developmentSections, sortHouses, unseenBadge } from '../features/works/work-sections';
import { BrandComponent } from '../shared/ui/brand.component';
import { ImagePipe } from '../shared/media/variant.pipe';

/**
 * Menú lateral del dueño: Inicio, sus desarrollos (cada uno con lo sin ver) y, en el
 * abierto, sus secciones; dentro de Casas o de una casa, la lista de casas. Abajo, la
 * persona con su cuenta.
 */
@Component({
  selector: 'app-owner-sidebar',
  imports: [ImagePipe, RouterLink, RouterLinkActive, MatIconModule, MatMenuModule, BrandComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a [routerLink]="context.homeUrl()" class="brand" aria-label="Ir al inicio" (click)="navigated.emit()"><app-brand /></a>

    <nav class="nav" aria-label="Principal">
      <a class="item" [routerLink]="context.homeUrl()" routerLinkActive="active" ariaCurrentWhenActive="page"
         [routerLinkActiveOptions]="{ exact: true }" (click)="navigated.emit()">
        <mat-icon svgIcon="home" aria-hidden="true" />Inicio
      </a>

      <p class="group-title" id="developments-title">Desarrollos</p>
      <ul class="developments" aria-labelledby="developments-title">
        @for (development of nav.developments(); track development.id) {
          <li [class.open]="development.id === nav.openId()">
            <a class="item development" [routerLink]="['/obras', development.id]" (click)="navigated.emit()"
               [attr.aria-current]="development.id === nav.openId() ? 'true' : null">
              @if (development.coverUrl) {
                <img class="thumb" [src]="development | image: 'cover' : 'thumb'" alt="" />
              } @else {
                <span class="thumb" aria-hidden="true"></span>
              }
              <span class="name">{{ development.name }}</span>
              @if (development.id !== nav.openId()) {
                @if (badgeOf(development); as badge) {
                  <span class="count ov-num" [attr.aria-label]="badge + ' sin ver'">{{ badge }}</span>
                }
              }
            </a>
            @if (development.id === nav.openId()) {
              <ul class="sections" [attr.aria-label]="'Secciones de ' + development.name">
                @for (section of sectionsOf(development); track section.path) {
                  <li>
                    <a class="section" [routerLink]="link(development, section.path)" routerLinkActive="active"
                       [routerLinkActiveOptions]="{ exact: section.exact }" ariaCurrentWhenActive="page" (click)="navigated.emit()">
                      {{ section.label }}
                      @if (section.badge; as badge) {
                        <span class="count ov-num" [attr.aria-label]="badge + ' sin ver'">{{ badge }}</span>
                      }
                    </a>
                    @if (section.path === 'casas' && nav.inHouses() && houses().list.length > 0) {
                      <ul class="houses" aria-label="Casas">
                        @for (unit of houses().list; track unit.id) {
                          <li>
                            <a class="house" [routerLink]="['/obras', development.id, 'casas', unit.id]" routerLinkActive="active"
                               ariaCurrentWhenActive="page" (click)="navigated.emit()">{{ unit.code }}</a>
                          </li>
                        }
                        @if (houses().more > 0) {
                          <li>
                            <a class="house all" [routerLink]="['/obras', development.id, 'casas']" (click)="navigated.emit()">
                              Ver todas ({{ houses().total }})
                            </a>
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
            }
          </li>
        }
      </ul>

      @if (nav.owns()) {
        <a class="item create" routerLink="/desarrollos" [queryParams]="{ nuevo: 1 }" (click)="navigated.emit()">
          <mat-icon svgIcon="add" aria-hidden="true" />Nuevo desarrollo
        </a>
      }
    </nav>

    <button type="button" class="person" [matMenuTriggerFor]="accountMenu" [attr.aria-label]="'Cuenta de ' + (user()?.fullName ?? '')">
      <span class="avatar" aria-hidden="true">{{ initials() }}</span>
      <span class="who">
        <strong>{{ user()?.fullName }}</strong>
        <span>{{ user()?.email }}</span>
      </span>
    </button>
    <mat-menu #accountMenu="matMenu" yPosition="above">
      @if (context.supervises()) {
        <a mat-menu-item routerLink="/hoy" (click)="navigated.emit()">Hoy</a>
      }
      @if (context.units().length === 1) {
        <a mat-menu-item [routerLink]="['/mi-propiedad', context.units()[0].unitId]" (click)="navigated.emit()">Mi propiedad</a>
      } @else if (context.units().length > 1) {
        <a mat-menu-item routerLink="/mis-propiedades" (click)="navigated.emit()">Mis propiedades</a>
      }
      <a mat-menu-item routerLink="/cuenta" (click)="navigated.emit()">Mi cuenta</a>
      <button mat-menu-item type="button" (click)="logout.emit()">Salir</button>
    </mat-menu>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--ov-space-5);
      height: 100%;
      padding: var(--ov-space-5) var(--ov-space-3);
      overflow-y: auto;
      background: var(--ov-surface);
    }

    .brand {
      padding: var(--ov-space-1) var(--ov-space-3);
      text-decoration: none;
    }

    .nav {
      display: grid;
      align-content: start;
      gap: var(--ov-space-1);
    }

    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .item {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 2.5rem;
      padding: 0 var(--ov-space-3);
      border-radius: var(--ov-radius-sm);
      color: var(--ov-ink);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .item mat-icon {
      width: 1.25rem;
      height: 1.25rem;
      flex: none;
    }

    .item:hover {
      background: var(--ov-surface-muted);
    }

    .item.active {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .group-title {
      margin-top: var(--ov-space-4);
      padding: 0 var(--ov-space-3) var(--ov-space-1);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .developments {
      display: grid;
      gap: var(--ov-space-1);
    }

    .developments > li.open {
      padding: var(--ov-space-1);
      border-radius: var(--ov-radius-md);
      background: var(--ov-canvas);
    }

    .development .name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .open > .development {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .thumb {
      flex: none;
      width: 1.625rem;
      height: 1.625rem;
      border-radius: var(--ov-radius-sm);
      object-fit: cover;
      background: var(--ov-surface-muted);
    }

    .count {
      margin-left: auto;
      padding: 0.0625rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent);
      color: var(--ov-on-ink);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .sections {
      display: grid;
      padding: 0 0 var(--ov-space-1) 2.75rem;
    }

    .section,
    .house {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      min-height: 2.125rem;
      padding-right: var(--ov-space-2);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .section:hover,
    .house:hover {
      color: var(--ov-ink);
    }

    .section.active,
    .house.active {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .houses {
      padding-left: var(--ov-space-3);
      border-left: 1px solid var(--ov-line);
    }

    .house {
      min-height: 2rem;
    }

    .house.all {
      color: var(--ov-accent);
    }

    .create {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .person {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      margin-top: auto;
      padding: var(--ov-space-3);
      border: 0;
      border-top: 1px solid var(--ov-line);
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .avatar {
      display: grid;
      flex: none;
      place-items: center;
      width: 2rem;
      height: 2rem;
      border-radius: 50%;
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .who {
      display: grid;
      min-width: 0;
      font-size: var(--ov-text-xs);
      line-height: 1.3;
    }

    .who strong {
      font-size: var(--ov-text-sm);
    }

    .who span {
      overflow: hidden;
      color: var(--ov-ink-muted);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    a:focus-visible,
    button:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }
  `,
})
export class OwnerSidebarComponent {

  protected readonly nav = inject(OwnerNavStore);
  protected readonly context = inject(UserContextService);
  private readonly auth = inject(AuthService);

  /** Al tocar un destino: en el teléfono, el panel se cierra. */
  readonly navigated = output<void>();
  readonly logout = output<void>();

  protected readonly user = this.auth.currentUser;
  protected readonly initials = computed(() => {
    const words = (this.user()?.fullName ?? '').trim().split(/\s+/).filter(Boolean);
    return words.slice(0, 2).map(word => word[0].toUpperCase()).join('');
  });

  /** Hasta doce casas; con más, "Ver todas" lleva a la pestaña Casas. */
  protected readonly houses = computed(() => {
    const units = sortHouses(this.nav.units());
    return { list: units.slice(0, MENU_HOUSES), more: Math.max(0, units.length - MENU_HOUSES), total: units.length };
  });

  protected sectionsOf(development: Development) {
    return developmentSections(development, this.nav.unseen()[development.id] ?? null);
  }

  protected badgeOf(development: Development): string | null {
    return development.role === 'OWNER' ? unseenBadge(this.nav.unseen()[development.id]) : null;
  }

  protected link(development: Development, path: string): string[] {
    return path ? ['/obras', development.id, path] : ['/obras', development.id];
  }
}
