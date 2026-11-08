import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { BrandComponent } from '../../shared/ui/brand.component';
import { AccountMenuComponent } from '../../shell/account-menu.component';
import { FieldHouseStore } from './field-house.store';

/**
 * Marco del trabajo de campo (`/hoy`), pensado para el teléfono.
 *
 * <p>En el teléfono no hay barra de arriba: abajo va una barra fija con Hoy, La casa y Mis
 * publicaciones, sólo en esas tres secciones (las rutas con `data.tabs`); publicar, el recorrido
 * y el audio son subpantallas con su "volver" y su propio pie. En escritorio va una barra arriba
 * con las mismas secciones, el selector de casa y la cuenta.
 *
 * <p>La casa elegida es la de `FieldHouseStore`: entrar a `/hoy/:unitId/...` la elige.
 */
@Component({
  selector: 'app-field-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, BrandComponent, AccountMenuComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="topbar">
      <div class="bar">
        <a routerLink="/hoy" class="brand-link ov-hit" aria-label="Hoy"><app-brand /></a>
        <nav class="sections" aria-label="Trabajo de campo">
          @for (section of sections(); track section.label) {
            @if (section.link; as link) {
              <a [routerLink]="link" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: section.exact }"
                 ariaCurrentWhenActive="page">{{ section.label }}</a>
            }
          }
        </nav>
        @if (store.houses().length > 1) {
          <label class="house-pick">
            <span>Casa</span>
            <select class="ov-input" (change)="choose($any($event.target))">
              @for (house of store.houses(); track house.unitId) {
                <option [value]="house.unitId" [selected]="house.unitId === store.selected()?.unitId">
                  {{ house.code }} · {{ house.developmentName }}
                </option>
              }
            </select>
          </label>
        } @else if (store.selected(); as house) {
          <span class="house-name">{{ house.code }} · {{ house.developmentName }}</span>
        }
        <app-account-menu />
      </div>
    </header>

    <main class="body" [class.with-tabs]="tabs()">
      <router-outlet />
    </main>

    @if (tabs()) {
      <nav class="tabbar" aria-label="Trabajo de campo">
        @for (section of sections(); track section.label) {
          @if (section.link; as link) {
            <a [routerLink]="link" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: section.exact }"
               ariaCurrentWhenActive="page">
              <mat-icon [svgIcon]="section.icon" aria-hidden="true" />
              <span>{{ section.label }}</span>
            </a>
          } @else {
            <span class="tab-off" aria-disabled="true">
              <mat-icon [svgIcon]="section.icon" aria-hidden="true" />
              <span>{{ section.label }}</span>
            </span>
          }
        }
      </nav>
    }
  `,
  styles: `
    :host {
      display: block;
      min-height: 100dvh;
    }

    .topbar {
      display: none;
    }

    .body.with-tabs {
      padding-bottom: calc(4.25rem + env(safe-area-inset-bottom));
    }

    .tabbar {
      position: fixed;
      right: 0;
      bottom: 0;
      left: 0;
      z-index: 10;
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      padding-bottom: env(safe-area-inset-bottom);
      border-top: 1px solid var(--ov-line);
      background: var(--ov-surface);
    }

    .tabbar a,
    .tab-off {
      display: grid;
      justify-items: center;
      align-content: center;
      gap: 2px;
      min-height: 4rem;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-medium);
      text-decoration: none;
    }

    .tabbar a.active {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .tab-off {
      opacity: 0.5;
    }

    .tabbar mat-icon {
      width: 1.5rem;
      height: 1.5rem;
    }

    @media (min-width: 60rem) {
      .topbar {
        position: sticky;
        top: 0;
        z-index: 10;
        display: block;
        border-bottom: 1px solid var(--ov-line);
        background: color-mix(in srgb, var(--ov-canvas) 90%, transparent);
        backdrop-filter: blur(12px);
      }

      .bar {
        display: flex;
        align-items: center;
        gap: var(--ov-space-6);
        max-width: 75rem;
        height: var(--ov-header-height);
        margin: 0 auto;
        padding: 0 var(--ov-gutter);
      }

      .sections {
        display: flex;
        flex: 1;
        gap: var(--ov-space-5);
      }

      .sections a {
        display: inline-flex;
        align-items: center;
        min-height: var(--ov-header-height);
        border-bottom: 2px solid transparent;
        color: var(--ov-ink-muted);
        text-decoration: none;
      }

      .sections a.active {
        border-bottom-color: var(--ov-ink);
        color: var(--ov-ink);
        font-weight: var(--ov-weight-semibold);
      }

      .house-pick {
        display: flex;
        align-items: center;
        gap: var(--ov-space-2);
        color: var(--ov-ink-muted);
        font-size: var(--ov-text-sm);
      }

      .house-pick .ov-input {
        min-height: 2.5rem;
        width: auto;
        padding-right: var(--ov-space-6);
      }

      .house-name {
        color: var(--ov-ink-muted);
        font-size: var(--ov-text-sm);
      }

      .body.with-tabs {
        padding-bottom: 0;
      }

      .tabbar {
        display: none;
      }
    }
  `,
})
export class FieldLayoutComponent implements OnInit {
  protected readonly store = inject(FieldHouseStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /** Si la pantalla actual es una de las tres secciones, que llevan la barra de abajo. */
  protected readonly tabs = signal(false);

  protected readonly sections = computed(() => {
    const unitId = this.store.selected()?.unitId ?? null;
    return [
      { label: 'Hoy', icon: 'light_mode', link: ['/hoy'], exact: true },
      { label: 'La casa', icon: 'home', link: unitId ? ['/hoy', unitId, 'casa'] : null, exact: false },
      { label: 'Mis publicaciones', icon: 'list', link: unitId ? ['/hoy', unitId, 'publicaciones'] : null, exact: false },
    ];
  });

  ngOnInit(): void {
    this.store.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => {} });
    this.follow();
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.follow());
  }

  /**
   * Si se estaba mirando otra casa, se pasa a la misma pantalla de la elegida: la navegación
   * pasa por el aviso de cambios sin guardar, y si la persona se queda, el selector vuelve a
   * la casa en la que estaba. La elegida la fija `follow()` al llegar.
   */
  protected choose(select: HTMLSelectElement): void {
    const unitId = select.value;
    const current = deepest(this.router.routerState.snapshot.root);
    const viewing = current.paramMap.get('unitId');
    if (!viewing || viewing === unitId) {
      this.store.select(unitId);
      return;
    }
    void this.router.navigateByUrl(this.router.url.replace(`/hoy/${viewing}`, `/hoy/${unitId}`)).then(moved => {
      if (!moved) {
        select.value = viewing;
      }
    });
  }

  /** La casa de la dirección pasa a ser la elegida; la barra de abajo, según la pantalla. */
  private follow(): void {
    const current = deepest(this.router.routerState.snapshot.root);
    const unitId = current.paramMap.get('unitId');
    if (unitId) {
      this.store.select(unitId);
    }
    this.tabs.set(current.data['tabs'] === true);
  }
}

function deepest(route: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
  let current = route;
  while (current.firstChild) {
    current = current.firstChild;
  }
  return current;
}
