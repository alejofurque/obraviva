import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/session/user-context.service';
import { MyUnitSummary } from '../../core/api/api.models';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';

/**
 * Inicio de quien tiene obras y propiedades a la vez, o todavía nada. Con una sola de
 * las dos cosas la persona entra directo a ella y no pasa por acá.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, LoadingStateComponent, EmptyStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page">
      <p class="ov-eyebrow">{{ greeting() }}</p>
      <h1 class="title">Todo en un lugar</h1>

      @switch (state()) {
        @case ('loading') {
          <app-loading-state label="Cargando tus obras y propiedades…" />
        }
        @case ('error') {
          <app-error-state [message]="errorMessage()" (retry)="load()" />
        }
        @case ('empty') {
          <app-empty-state
            title="Todavía no hay nada para mostrarte"
            message="Cuando una desarrolladora te sume a su equipo o vincule tu casa, la vas a ver acá."
          />
        }
        @default {
          <div class="grid">
            @if (developments().length > 0) {
              <a class="ov-card tile" routerLink="/desarrollos">
                <p class="ov-eyebrow">Obras</p>
                <p class="count ov-num">{{ developments().length }}</p>
                <p class="ov-lead">
                  {{ developments().length === 1 ? 'Obra que administrás o seguís.' : 'Obras que administrás o seguís.' }}
                </p>
                <span class="go">Ver obras</span>
              </a>
            }
            @if (units().length > 0) {
              <a class="ov-card tile" [routerLink]="unitsLink()">
                <p class="ov-eyebrow">Propiedades</p>
                <p class="count ov-num">{{ units().length }}</p>
                <p class="ov-lead">
                  {{ units().length === 1 ? unitLabel(units()[0]) : 'Propiedades a tu nombre.' }}
                </p>
                <span class="go">{{ units().length === 1 ? 'Ver mi propiedad' : 'Ver propiedades' }}</span>
              </a>
            }
          </div>
        }
      }
    </div>
  `,
  styles: `
    .title {
      margin: var(--ov-space-3) 0 var(--ov-space-7);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 20rem), 1fr));
      gap: var(--ov-space-5);
    }

    .tile {
      display: grid;
      gap: var(--ov-space-2);
      padding: var(--ov-space-6);
      color: inherit;
      text-decoration: none;
      transition: border-color var(--ov-duration-fast) var(--ov-ease);
    }

    .tile:hover {
      border-color: var(--ov-line-strong);
    }

    .count {
      margin: var(--ov-space-2) 0 0;
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
      line-height: 1;
    }

    .go {
      margin-top: var(--ov-space-4);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }
  `,
})
export class HomeComponent implements OnInit {

  private readonly auth = inject(AuthService);
  private readonly context = inject(UserContextService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly developments = this.context.developments;
  protected readonly units = this.context.units;

  /** Una obra única se nombra por la obra; una unidad, por su código dentro del edificio. */
  protected unitLabel(unit: MyUnitSummary): string {
    return unit.developmentKind === 'SINGLE' ? unit.developmentName : unit.unitCode + ' · ' + unit.developmentName;
  }

  protected readonly loading = signal(true);
  protected readonly errorMessage = signal('');

  protected readonly state = computed(() => {
    if (this.loading()) {
      return 'loading';
    }
    if (this.errorMessage()) {
      return 'error';
    }
    return this.developments().length === 0 && this.units().length === 0 ? 'empty' : 'ready';
  });

  protected readonly unitsLink = computed(() => {
    const units = this.units();
    return units.length === 1 ? ['/mi-propiedad', units[0].unitId] : ['/mis-propiedades'];
  });

  protected readonly greeting = computed(() => {
    const firstName = (this.auth.currentUser()?.fullName ?? '').trim().split(/\s+/)[0] ?? '';
    const hour = new Date().getHours();
    const salutation = hour < 12 ? 'Buenos días' : hour < 20 ? 'Buenas tardes' : 'Buenas noches';
    return firstName ? `${salutation}, ${firstName}` : salutation;
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.context
      .refresh()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.loading.set(false),
        error: (error: unknown) => {
          this.loading.set(false);
          this.errorMessage.set(errorMessageOf(error, 'No se pudieron cargar tus obras y propiedades.'));
        },
      });
  }
}
