import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { ScrollFadeDirective } from '../../shared/ui/scroll-fade.directive';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { HouseStore } from './house.store';
import { SALES_STATUS_LABELS } from './work-labels';
import { WorkSection, houseTabs } from './work-sections';
import { WorkStore } from './work.store';
import { ImagePipe } from '../../shared/media/variant.pipe';

/** En qué punto está la obra de una casa, para el chip del encabezado; nulo sin etapas. */
export function workState(progressPercent: number | null, stageCount: number | null): string | null {
  if (!stageCount) {
    return null;
  }
  const percent = progressPercent ?? 0;
  return percent >= 100 ? 'Terminada' : percent > 0 ? 'En obra' : 'Sin empezar';
}

/**
 * Espacio de trabajo de una casa: la casa es una obra independiente, con sus etapas, su
 * historia, sus lugares y recorridos 3D, sus novedades, su ficha de venta y su comprador.
 *
 * <p>Provee el desarrollo y la casa a sus pestañas. El encargado ve lo que carga (Resumen,
 * Etapas, Historia, Lugares y 3D, Novedades); ficha, venta, gasto, el estado comercial y
 * "Ver como el comprador" son del dueño.
 */
@Component({
  selector: 'app-house-layout',
  imports: [ImagePipe, ScrollFadeDirective, RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, MatMenuModule, PageHeaderComponent, LoadingStateComponent, ErrorStateComponent],
  providers: [WorkStore, HouseStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (work.development(); as development) {
      @if (house.unit(); as unit) {
        <div class="head">
          <div class="head-inner">
            <app-page-header [heading]="unit.code" [imageUrl]="unit | image: 'cover' : 'thumb'" [wideImage]="true" [compact]="true">
              <span header-context>
                <a class="context-link" [routerLink]="['/obras', development.id]">{{ development.name }}</a>@if (unit.modelName) { · {{ unit.modelName }}}@if (unit.coveredAreaM2 ?? unit.areaM2; as area) { · {{ area }} m²}
              </span>
              @if (work.isOwner() && salesLabel()) {
                <span header-states class="ov-pill sales" [attr.data-status]="unit.salesStatus">{{ salesLabel() }}</span>
              }
              @if (stateLabel(); as state) {
                <span header-states class="ov-pill ov-pill--accent work-state">{{ state }}</span>
              }
              <div header-actions class="progress">
                <p class="progress-line"><span>Avance</span><span class="ov-num percent">{{ unit.progressPercent ?? 0 }}%</span></p>
                <div class="ov-progress" aria-hidden="true"><span [style.width.%]="unit.progressPercent ?? 0"></span></div>
                <p class="stage">{{ stageLine() }}</p>
              </div>
              @if (work.isOwner()) {
                <a header-actions class="ov-btn ov-btn--secondary as-buyer" [routerLink]="['/mi-propiedad', unit.id]" [queryParams]="{ obra: development.id }">
                  Ver como el comprador
                </a>
              }
              <!-- En el teléfono, "Ver como el comprador" va en el menú de la casa. -->
              @if (work.isOwner()) {
                <button header-actions type="button" class="more" [matMenuTriggerFor]="houseMenu" aria-label="Más acciones de la casa">
                  <mat-icon svgIcon="more_vert" aria-hidden="true" />
                </button>
              }
            </app-page-header>
            <mat-menu #houseMenu="matMenu" xPosition="before">
              <a mat-menu-item [routerLink]="['/mi-propiedad', unit.id]" [queryParams]="{ obra: development.id }">Ver como el comprador</a>
            </mat-menu>
          </div>

          <nav class="ov-tabs tabs" appScrollFade aria-label="Secciones de la casa">
            @for (tab of tabs(); track tab.path) {
              <a
                [routerLink]="link(tab.path)"
                routerLinkActive="active"
                [routerLinkActiveOptions]="{ exact: tab.exact }"
                ariaCurrentWhenActive="page"
              >{{ tab.label }}</a>
            }
          </nav>
        </div>

        <router-outlet />
      } @else if (house.error(); as error) {
        <div class="ov-page">
          <app-error-state [message]="error.message" [retryable]="error.retryable" (retry)="house.reload()" />
        </div>
      } @else {
        <app-loading-state label="Cargando la casa…" />
      }
    } @else if (work.error(); as error) {
      <div class="ov-page">
        <app-error-state [message]="error.message" [retryable]="error.retryable" (retry)="work.reload()" />
      </div>
    } @else {
      <app-loading-state label="Cargando la casa…" />
    }
  `,
  styles: `
    .head {
      border-bottom: 1px solid var(--ov-line);
      background: var(--ov-surface);
    }

    .head-inner,
    .tabs {
      max-width: var(--ov-content-max);
      margin: 0 auto;
      padding: 0 var(--ov-gutter);
    }

    .head-inner {
      padding-top: var(--ov-space-6);
      padding-bottom: var(--ov-space-4);
    }

    .context-link {
      color: inherit;
    }

    .context-link:hover {
      color: var(--ov-ink);
    }

    .sales[data-status='SOLD'] {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .progress {
      display: grid;
      gap: var(--ov-space-1);
      min-width: 11rem;
    }

    .progress-line {
      display: flex;
      justify-content: space-between;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .percent {
      color: var(--ov-ink);
      font-weight: var(--ov-weight-semibold);
    }

    .stage {
      max-width: 16rem;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .more {
      display: none;
      place-items: center;
      width: 2.75rem;
      height: 2.75rem;
      padding: 0;
      border: 1px solid var(--ov-line);
      border-radius: 50%;
      background: var(--ov-surface);
      color: var(--ov-ink);
      cursor: pointer;
    }

    /* Teléfono: el nombre y el avance en una línea; lo demás, en el menú de la casa. */
    @media (max-width: 40rem) {
      .head-inner {
        padding-top: var(--ov-space-4);
        padding-bottom: var(--ov-space-3);
      }

      .progress {
        min-width: 0;
        justify-items: end;
      }

      /* "En obra" repite lo que dice el porcentaje: en el teléfono no entra en la línea. */
      .work-state,
      .progress-line > span:first-child,
      .stage,
      .as-buyer {
        display: none;
      }

      .progress .ov-progress {
        width: 3.5rem;
      }

      .more {
        display: grid;
      }
    }
  `,
})
export class HouseLayoutComponent implements OnInit {

  protected readonly work = inject(WorkStore);
  protected readonly house = inject(HouseStore);
  private readonly route = inject(ActivatedRoute);

  protected readonly salesLabel = computed(() => {
    const unit = this.house.unit();
    return unit?.salesStatus ? SALES_STATUS_LABELS[unit.salesStatus] : '';
  });

  protected readonly stageLine = computed(() => {
    const unit = this.house.unit();
    if (!unit || !unit.stageCount) {
      return 'Sin etapas cargadas';
    }
    if ((unit.progressPercent ?? 0) >= 100) {
      return 'Obra terminada';
    }
    return unit.currentStageName ?? 'Sin etapa en curso';
  });

  protected readonly tabs = computed<WorkSection[]>(() => houseTabs(this.work.isOwner()));

  protected readonly stateLabel = computed(() => {
    const unit = this.house.unit();
    return unit ? workState(unit.progressPercent, unit.stageCount) : null;
  });

  ngOnInit(): void {
    const params = this.route.snapshot.paramMap;
    this.work.load(params.get('developmentId') ?? '');
    this.house.load(params.get('unitId') ?? '', params.get('developmentId'));
  }

  protected link(path: string): string[] {
    const base = ['/obras', this.route.snapshot.paramMap.get('developmentId') ?? '', 'casas', this.house.unitId()];
    return path ? [...base, path] : base;
  }
}
