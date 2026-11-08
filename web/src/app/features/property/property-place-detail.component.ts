import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { todayLocal } from '../../shared/time/local-date';
import { PropertyPlacesComponent } from './property-places.component';
import { stageJourney } from './property-derivations';
import { PropertyViewStore } from './property-view.store';
import { StageJourneyComponent } from './stage-journey.component';

/**
 * El detalle de un lugar, mes a mes: el mismo "lugar por lugar" de siempre, abierto en el
 * lugar de la tarjeta que se tocó en "Mi casa" (`?lugar=`).
 */
@Component({
  selector: 'app-property-place-detail',
  imports: [RouterLink, PropertyPlacesComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page detail">
      <a class="back ov-hit" [routerLink]="store.basePath()" [queryParams]="store.navQuery()">{{ store.homeLabel() }}</a>
      @if (store.view(); as view) {
        <app-property-places [places]="view.places" [tourPath]="[...store.basePath(), 'recorrido']"
                             [initialPlace]="place()" (failed)="store.renewAfterFailure($event)" />
      }
    </div>
  `,
  styles: `
    .detail {
      display: grid;
      gap: var(--ov-space-4);
      padding-top: var(--ov-space-5);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }
  `,
})
export class PropertyPlaceDetailComponent {
  protected readonly store = inject(PropertyViewStore);
  protected readonly place = toSignal(inject(ActivatedRoute).queryParamMap.pipe(map(params => params.get('lugar'))),
    { initialValue: null });
}

/** Todas las etapas, con lo que incluye cada una y sus fechas: el "Ver todas las etapas" de "Mi casa". */
@Component({
  selector: 'app-property-stages',
  imports: [RouterLink, StageJourneyComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page detail">
      <a class="back ov-hit" [routerLink]="store.basePath()" [queryParams]="store.navQuery()">{{ store.homeLabel() }}</a>
      @if (steps().length > 0) {
        <app-stage-journey [steps]="steps()" />
      }
    </div>
  `,
  styles: `
    .detail {
      display: grid;
      gap: var(--ov-space-4);
      padding-top: var(--ov-space-5);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }
  `,
})
export class PropertyStagesComponent {
  protected readonly store = inject(PropertyViewStore);
  protected readonly steps = computed(() => stageJourney(this.store.view()?.stages ?? [], todayLocal()));
}
