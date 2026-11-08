import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { BackLinkDirective } from '../../core/routing/back-navigation';
import { UserContextService } from '../../core/session/user-context.service';
import { AccountMenuComponent } from '../../shell/account-menu.component';
import { BrandComponent } from '../../shared/ui/brand.component';
import { loadDisplayFont } from '../../shared/ui/display-font';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { propertyContext, propertyTitle } from './property-derivations';
import { PropertyViewStore } from './property-view.store';

/** Una sección de la propiedad, con su ícono para la barra del teléfono. */
export interface BuyerSection {
  label: string;
  path: string;
  icon: string;
  exact: boolean;
}

/**
 * Las cuatro secciones: Mi casa, Recorrer en 3D, Historia y, para el comprador, Documentos;
 * en el enlace público, que no tiene documentos, La casa. "La casa" en Mi propiedad se abre
 * desde el cierre de "Mi casa"; Compartir es un botón; Planos, lugares y etapas son subpáginas.
 */
export function buyerSections(isPublic: boolean): BuyerSection[] {
  return [
    // Quien abre un enlace no es el dueño: para esa persona es "La casa".
    { label: isPublic ? 'La casa' : 'Mi casa', path: '', icon: 'home', exact: true },
    { label: 'Recorrer en 3D', path: 'recorrido', icon: 'view_in_ar', exact: false },
    { label: 'Historia', path: 'historia', icon: 'history', exact: false },
    isPublic
      ? { label: 'Planos y barrio', path: 'casa', icon: 'apartment', exact: false }
      : { label: 'Documentos', path: 'documentos', icon: 'description', exact: false },
  ];
}

/**
 * Marco de una propiedad. Lo usan "Mi propiedad", "Ver como el comprador" y el enlace público:
 * el backend les devuelve la misma proyección, así que comparten pantallas. Lo único que
 * cambia es de dónde sale la vista, que el enlace público no tiene documentos ni compartir, y
 * que el dueño tiene la vuelta a la casa.
 *
 * <p>Una sola barra arriba, como en el boceto: la marca, las cuatro secciones, "Compartir" y la
 * cuenta (el shell no dibuja la suya acá). En el teléfono las secciones van en una barra fija
 * abajo, con objetivos de 44 px, y "Compartir" queda como botón redondo. En "Mi casa" la
 * portada hace de título; en las demás secciones, el nombre de la casa va arriba.
 */
@Component({
  selector: 'app-property-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BackLinkDirective, MatIconModule, BrandComponent, AccountMenuComponent, LoadingStateComponent, ErrorStateComponent],
  providers: [PropertyViewStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="bar">
      <div class="bar-inner">
        @if (store.isPublic()) {
          <app-brand class="brand" />
        } @else {
          <a class="brand ov-hit" [routerLink]="userContext.homeUrl()" aria-label="Ir al inicio"><app-brand /></a>
        }
        @if (store.view()) {
          <nav class="sections" aria-label="Secciones de la propiedad">
            @for (section of sections(); track section.path) {
              <a [routerLink]="link(section.path)" [queryParams]="store.navQuery()" routerLinkActive="active"
                 [routerLinkActiveOptions]="{ exact: section.exact }" ariaCurrentWhenActive="page">{{ section.label }}</a>
            }
          </nav>
        }
        @if (store.isPublic()) {
          <span class="ov-eyebrow end">Vista compartida</span>
        } @else {
          @if (store.view()) {
            <a class="ov-btn ov-btn--secondary share end" [routerLink]="link('compartir')" [queryParams]="store.navQuery()">
              <mat-icon svgIcon="ios_share" aria-hidden="true" /><span class="share-label">Compartir</span>
            </a>
          }
          <app-account-menu class="account" [class.end]="!store.view()" />
        }
      </div>
      @if (store.view() && !store.isPublic() && (fromField || workId)) {
        <p class="preview">
          @if (fromField) {
            <a class="back ov-hit" routerLink="/hoy">Volver a Hoy</a>
          } @else {
            <a class="back ov-hit" [appBack]="['/obras', workId!, 'casas', route.snapshot.paramMap.get('unitId') ?? '']">Volver a la casa</a>
          }
          <span>Estás viendo lo mismo que ve el comprador.</span>
        </p>
      }
    </header>

    @if (store.view(); as view) {

      @if (!onHome()) {
        <div class="head">
          @if (context(view); as line) {
            <p class="ov-eyebrow">{{ line }}</p>
          }
          <p class="ov-display title">{{ title(view) }}</p>
        </div>
      }

      <router-outlet />

      <nav class="tabbar" aria-label="Secciones">
        @for (section of sections(); track section.path) {
          <a [routerLink]="link(section.path)" [queryParams]="store.navQuery()" routerLinkActive="active"
             [routerLinkActiveOptions]="{ exact: section.exact }" ariaCurrentWhenActive="page">
            <mat-icon [svgIcon]="section.icon" aria-hidden="true" />
            <span>{{ section.path === 'recorrido' ? '3D' : section.label }}</span>
          </a>
        }
      </nav>
    } @else if (store.error(); as error) {
      <div class="ov-page">
        <app-error-state tone="neutral" [message]="error.message" [retryable]="error.retryable" (retry)="store.reload()" />
      </div>
    } @else {
      <app-loading-state label="Cargando la propiedad…" />
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .bar-inner,
    .preview,
    .head {
      max-width: var(--ov-content-max);
      margin: 0 auto;
      padding: 0 var(--ov-gutter);
    }

    .bar {
      position: sticky;
      top: 0;
      z-index: 10;
      border-bottom: 1px solid var(--ov-line);
      background: color-mix(in srgb, var(--ov-canvas) 86%, transparent);
      backdrop-filter: blur(12px);
    }

    .bar-inner {
      display: flex;
      align-items: center;
      gap: var(--ov-space-5);
      min-height: var(--ov-header-height);
    }

    .brand {
      flex: none;
      color: inherit;
      text-decoration: none;
    }

    .end {
      margin-left: auto;
    }

    .preview {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-1) var(--ov-space-3);
      padding-bottom: var(--ov-space-3);
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .back {
      color: var(--ov-ink-muted);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .sections {
      display: flex;
      flex: 1;
      flex-wrap: wrap;
      gap: var(--ov-space-1);
      min-width: 0;
    }

    .sections a {
      display: inline-flex;
      align-items: center;
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border-radius: var(--ov-radius-pill);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .sections a.active {
      background: var(--ov-ink);
      color: var(--ov-on-ink);
      font-weight: var(--ov-weight-medium);
    }

    .share {
      flex: none;
      gap: var(--ov-space-2);
      min-height: 2.75rem;
    }

    .share mat-icon {
      width: 1.125rem;
      height: 1.125rem;
    }

    .head {
      display: grid;
      gap: var(--ov-space-1);
      padding-top: var(--ov-space-6);
    }

    .title {
      font-size: var(--ov-text-2xl);
    }

    .tabbar {
      display: none;
    }

    a:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    /* Teléfono: las secciones bajan a una barra fija, al alcance del pulgar. */
    @media (max-width: 40rem) {
      :host {
        padding-bottom: calc(4.5rem + env(safe-area-inset-bottom));
      }

      .sections {
        display: none;
      }

      .bar-inner {
        gap: var(--ov-space-3);
      }

      /* "Compartir" redondo, como en el boceto; el texto queda para el lector de pantalla. */
      .share {
        width: 2.75rem;
        padding: 0;
        justify-content: center;
      }

      .share-label {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }

      .tabbar {
        position: fixed;
        right: 0;
        bottom: 0;
        left: 0;
        z-index: 20;
        display: grid;
        grid-template-columns: repeat(4, minmax(0, 1fr));
        padding-bottom: env(safe-area-inset-bottom);
        border-top: 1px solid var(--ov-line);
        background: var(--ov-surface);
      }

      .tabbar a {
        display: grid;
        justify-items: center;
        align-content: center;
        gap: 0.125rem;
        min-height: 3.75rem;
        color: var(--ov-ink-muted);
        font-size: var(--ov-text-xs);
        text-decoration: none;
      }

      .tabbar a.active {
        color: var(--ov-accent);
        font-weight: var(--ov-weight-semibold);
      }

      .tabbar mat-icon {
        width: 1.5rem;
        height: 1.5rem;
      }

      .title {
        font-size: var(--ov-text-xl);
      }
    }
  `,
})
export class PropertyLayoutComponent implements OnInit {

  protected readonly store = inject(PropertyViewStore);
  protected readonly title = propertyTitle;
  protected readonly context = propertyContext;
  protected readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  protected readonly userContext = inject(UserContextService);

  /**
   * Obra desde la que el dueño abrió la vista del comprador. Sólo sirve para ofrecer la
   * vuelta: el acceso lo decide el backend, no este parámetro.
   */
  protected readonly workId = this.route.snapshot.queryParamMap.get('obra');
  /** El encargado que abrió la vista desde una tarea del campo: su vuelta es Hoy. */
  protected readonly fromField = this.route.snapshot.queryParamMap.get('desde') === 'hoy';

  protected readonly sections = computed(() => buyerSections(this.store.isPublic()));

  private readonly url = toSignal(
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), map(event => event.urlAfterRedirects)),
    { initialValue: this.router.url },
  );

  /** En "Mi casa" la portada hace de título: el encabezado de la casa no se repite. */
  protected readonly onHome = computed(() => this.url().split(/[?#]/)[0] === this.store.basePath().join('/'));

  ngOnInit(): void {
    loadDisplayFont(this.document);
    const params = this.route.snapshot.paramMap;
    const token = params.get('token');
    const query = this.route.snapshot.queryParamMap;
    this.store.keepQuery({ obra: query.get('obra'), desde: query.get('desde') });
    this.store.load(token !== null
      ? { kind: 'public', token }
      : { kind: 'private', unitId: params.get('unitId') ?? '' });
  }

  protected link(path: string): string[] {
    return path ? [...this.store.basePath(), path] : this.store.basePath();
  }

}
