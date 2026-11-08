import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { OwnerNavStore } from '../../core/session/owner-nav.store';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { ScrollFadeDirective } from '../../shared/ui/scroll-fade.directive';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { DEVELOPMENT_TYPE_LABELS } from './work-labels';
import { WorkSection, developmentSections } from './work-sections';
import { WorkStore } from './work.store';
import { ImagePipe } from '../../shared/media/variant.pipe';

/**
 * Espacio de trabajo de un desarrollo: el barrio con sus casas.
 *
 * <p>Cada casa es una obra propia y se trabaja en su propio espacio; acá se ve el conjunto
 * (Resumen, Casas), lo que es de todos (Obras comunes) y lo que es del dueño: el gasto y el
 * equipo. Para el dueño, las secciones están en el menú lateral (`OwnerNavStore`) y los
 * datos del desarrollo se editan desde el encabezado; quien no es dueño de ninguna obra las
 * ve como pestañas. Ocultar una sección es ayuda de interfaz: la restricción real está en el
 * servidor.
 */
@Component({
  selector: 'app-work-layout',
  imports: [ImagePipe, ScrollFadeDirective, RouterOutlet, RouterLink, RouterLinkActive, PageHeaderComponent, LoadingStateComponent, ErrorStateComponent],
  providers: [WorkStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.development(); as work) {
      <div class="head">
        <div class="head-inner">
          @if (!nav.active()) {
            <a class="back ov-hit" routerLink="/desarrollos">Tus desarrollos</a>
          }
          <app-page-header [heading]="work.name" [imageUrl]="work | image: 'cover' : 'thumb'">
            <span header-context>{{ subtitle() }}</span>
            @if (!store.isOwner()) {
              <span header-states class="ov-pill">Sos el encargado</span>
            }
            <!-- Un @if proyecta en un slot sólo con un único elemento raíz: uno por acción. -->
            @if (store.isOwner()) {
              <a header-actions class="ov-btn ov-btn--secondary" [routerLink]="link('datos')">Editar datos</a>
            }
            <!-- En Casas, "Agregar casas" es de la página: no van dos botones iguales. -->
            @if (store.isOwner() && !nav.inHouses()) {
              <a header-actions class="ov-btn ov-btn--primary" [routerLink]="link('casas')" [queryParams]="{ nueva: 1 }">Agregar casas</a>
            }
          </app-page-header>
        </div>

        @if (!nav.active()) {
          <nav class="ov-tabs tabs" appScrollFade aria-label="Secciones del desarrollo">
            @for (tab of tabs(); track tab.path) {
              <a
                [routerLink]="link(tab.path)"
                routerLinkActive="active"
                [routerLinkActiveOptions]="{ exact: tab.exact }"
                ariaCurrentWhenActive="page"
              >{{ tab.label }}@if (tab.badge; as badge) {<span class="badge ov-num" [attr.aria-label]="badge + ' sin ver'"> ●{{ badge }}</span>}</a>
            }
          </nav>
        }
      </div>

      <router-outlet />
    } @else if (store.error(); as error) {
      <div class="ov-page">
        <app-error-state [message]="error.message" [retryable]="error.retryable" (retry)="store.reload()" />
      </div>
    } @else {
      <app-loading-state label="Cargando el desarrollo…" />
    }
  `,
  styles: `
    .head-inner,
    .tabs {
      max-width: var(--ov-content-max);
      margin: 0 auto;
      padding: 0 var(--ov-gutter);
    }

    .head-inner {
      display: grid;
      gap: var(--ov-space-3);
      padding-top: var(--ov-space-6);
      padding-bottom: var(--ov-space-2);
    }

    .tabs {
      margin-top: var(--ov-space-3);
      border-bottom: 1px solid var(--ov-line);
    }

    .badge {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .back:hover {
      color: var(--ov-ink);
    }

  `,
})
export class WorkLayoutComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  protected readonly nav = inject(OwnerNavStore);
  private readonly route = inject(ActivatedRoute);

  /** Tipo y ubicación, en una línea: "Barrio cerrado · Villa Allende, Córdoba". */
  protected readonly subtitle = computed(() => {
    const work = this.store.development();
    if (!work) {
      return '';
    }
    const place = [work.locality, work.province].filter(Boolean).join(', ') || work.address;
    return [work.developmentType ? DEVELOPMENT_TYPE_LABELS[work.developmentType] : 'Desarrollo de casas', place]
      .filter(Boolean)
      .join(' · ');
  });

  protected readonly tabs = computed<WorkSection[]>(() => {
    const work = this.store.development();
    return work ? developmentSections(work, this.store.unseen()) : [];
  });

  ngOnInit(): void {
    this.store.load(this.route.snapshot.paramMap.get('developmentId') ?? '');
  }

  protected link(path: string): string[] {
    return path ? [...this.store.basePath(), path] : this.store.basePath();
  }
}
