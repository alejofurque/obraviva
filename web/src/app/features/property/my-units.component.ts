import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DOCUMENT } from '@angular/common';
import { RouterLink } from '@angular/router';
import { UserContextService } from '../../core/session/user-context.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { propertyTitle } from './property-derivations';
import { loadDisplayFont } from '../../shared/ui/display-font';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';

/** Propiedades a nombre de la persona, cuando tiene más de una. */
@Component({
  selector: 'app-my-units',
  imports: [RouterLink, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page">
      <p class="ov-eyebrow">Mis propiedades</p>
      <h1 class="ov-display title">Tus propiedades</h1>

      @if (loading()) {
        <app-loading-state label="Cargando tus propiedades…" />
      } @else if (errorMessage(); as message) {
        <app-error-state tone="neutral" [message]="message" (retry)="load()" />
      } @else if (context.units().length === 0) {
        <app-empty-state
          title="Todavía no tenés propiedades asociadas"
          message="Cuando la desarrolladora vincule tu casa a tu cuenta, la vas a ver acá." />
      } @else {
        <ul class="units">
          @for (unit of context.units(); track unit.unitId) {
            <li>
              <a class="ov-card unit" [routerLink]="['/mi-propiedad', unit.unitId]">
                <p class="ov-eyebrow">{{ unit.developmentKind === 'SINGLE' ? 'Obra' : 'Casa en ' + unit.developmentName }}</p>
                <p class="code">{{ title(unit) }}</p>
                @if (unit.unitDescription && unit.developmentKind === 'MULTI_UNIT') {
                  <p class="ov-lead">{{ unit.unitDescription }}</p>
                }
                @if (unit.developmentAddress) {
                  <p class="address">{{ unit.developmentAddress }}</p>
                }
                <span class="go">Ver propiedad</span>
              </a>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .title {
      margin: var(--ov-space-3) 0 var(--ov-space-7);
    }

    .units {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 20rem), 1fr));
      gap: var(--ov-space-5);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .unit {
      display: grid;
      gap: var(--ov-space-2);
      height: 100%;
      padding: var(--ov-space-6);
      color: inherit;
      text-decoration: none;
      transition: border-color var(--ov-duration-fast) var(--ov-ease);
    }

    .unit:hover {
      border-color: var(--ov-line-strong);
    }

    .code {
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.015em;
    }

    .address {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .go {
      margin-top: var(--ov-space-3);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }
  `,
})
export class MyUnitsComponent implements OnInit {

  protected readonly context = inject(UserContextService);
  protected readonly title = propertyTitle;
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    // Es del comprador: el título va en Fraunces, como en su vista.
    loadDisplayFont(inject(DOCUMENT));
  }

  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.context.refresh().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.loading.set(false),
      error: (error: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(errorMessageOf(error, 'No se pudieron cargar tus propiedades.'));
      },
    });
  }
}
