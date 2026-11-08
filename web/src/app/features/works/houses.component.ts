import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Unit, UnitSalesStatus } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { OwnerNavStore } from '../../core/session/owner-nav.store';
import { FieldHouseStore } from '../field/field-house.store';
import { errorMessageOf } from '../../shared/http/error-message';
import { replaceQuery } from '../../core/routing/query-state';
import { clearQuery } from '../../core/routing/query-state';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { HouseCardComponent } from './house-card.component';
import { SALES_STATUS_LABELS, SALES_STATUS_OPTIONS } from './work-labels';
import { WorkStore } from './work.store';
import { sortHouses } from './work-summary.component';

/**
 * Siguiente número libre para un prefijo: con "Casa 1" a "Casa 6" cargadas, sugiere 7.
 * Los nombres que no siguen el patrón no cuentan.
 */
export function nextHouseNumber(units: Pick<Unit, 'code'>[], prefix: string): number {
  const pattern = new RegExp(`^${prefix.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s+(\\d+)$`, 'i');
  const numbers = units.map(unit => unit.code.match(pattern)).filter(match => match !== null).map(match => Number(match![1]));
  return numbers.length === 0 ? 1 : Math.max(...numbers) + 1;
}

/** Los nombres que va a crear el alta, para mostrarlos antes de confirmar. */
export function batchNames(prefix: string, first: number, count: number): string[] {
  return Array.from({ length: Math.max(0, count) }, (_, index) => `${prefix.trim()} ${first + index}`);
}

type Filter = 'ALL' | UnitSalesStatus;

/**
 * Las casas del desarrollo. El dueño las carga de a varias, con las etapas estándar de
 * una casa ya aplicadas, y ve cómo va cada una en la venta. Entrar a una casa es entrar a
 * su obra.
 */
@Component({
  selector: 'app-houses',
  imports: [ReactiveFormsModule, HouseCardComponent, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page houses">
      <header class="intro">
        <div>
          <h2>Casas</h2>
          <p class="ov-lead">
            Cada casa es una obra independiente, con sus etapas, su historia, sus recorridos 3D y su comprador.
          </p>
        </div>
        <!-- Sin casas, la acción es la del estado vacío: una sola. -->
        @if (store.isOwner() && !adding() && units().length > 0) {
          <button class="ov-btn ov-btn--primary" type="button" (click)="startAdding()">Agregar casas</button>
        }
      </header>

      @if (adding()) {
        <section class="ov-card panel" aria-labelledby="add-title">
          <div>
            <h3 id="add-title">Agregar casas</h3>
            <p class="muted">Se crean con nombre y número correlativo. Después completás la ficha de cada una.</p>
          </div>
          <form [formGroup]="form" (ngSubmit)="create()" class="form" novalidate>
            <div class="ov-field">
              <label class="ov-eyebrow" for="batch-prefix">Nombre</label>
              <input id="batch-prefix" class="ov-input" formControlName="prefix" maxlength="40" placeholder="Casa, Lote…" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="batch-first">Desde el número</label>
              <input id="batch-first" class="ov-input" type="number" min="0" max="9999" formControlName="firstNumber" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="batch-count">Cantidad</label>
              <input id="batch-count" class="ov-input" type="number" min="1" max="200" formControlName="count" />
            </div>
            <label class="check wide">
              <input type="checkbox" formControlName="withStandardStages" />
              <span>
                <strong>Aplicar la plantilla de casa: 12 etapas y 4 sectores 3D</strong>
                <span class="muted block">
                  Preliminares, fundaciones, estructura, mampostería, techo, instalaciones, revoques, aberturas, pisos,
                  pintura y entrega, con su peso en el avance; y los sectores que se graban en cada recorrido: frente, living y cocina, dormitorio principal y baño. Todo se ajusta después, casa por casa.
                </span>
              </span>
            </label>

            @if (preview().length > 0) {
              <p class="preview wide" role="status">
                Se van a crear <strong>{{ preview().length === 1 ? preview()[0] : preview()[0] + ' a ' + preview()[preview().length - 1] }}</strong>
                ({{ preview().length }} {{ preview().length === 1 ? 'casa' : 'casas' }}).
              </p>
            }
            @if (formError(); as message) {
              <p class="ov-error wide" role="alert">{{ message }}</p>
            }
            <div class="actions wide">
              <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || form.invalid">
                {{ saving() ? 'Creando…' : 'Crear casas' }}
              </button>
              <button class="ov-link" type="button" (click)="adding.set(false)">Cancelar</button>
            </div>
          </form>
        </section>
      }

      @if (loading()) {
        <app-loading-state label="Cargando casas…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (units().length === 0) {
        @if (!adding()) {
          <app-empty-state title="Todavía no hay casas"
            [message]="store.isOwner() ? 'Cargá las casas del desarrollo: por ejemplo, diez casas de una vez con sus etapas estándar.' : 'El responsable todavía no cargó las casas.'">
            @if (store.isOwner()) {
              <button class="ov-btn ov-btn--primary" type="button" (click)="startAdding()">Agregar casas</button>
            }
          </app-empty-state>
        }
      } @else {
        @if (store.isOwner()) {
          <div class="filters" role="radiogroup" aria-label="Filtrar por venta">
            @for (option of filters; track option) {
              <button type="button" role="radio" class="filter" [class.active]="filter() === option"
                      [attr.aria-checked]="filter() === option" (click)="chooseFilter(option)">
                {{ filterLabel(option) }} <span class="ov-num count">{{ countOf(option) }}</span>
              </button>
            }
          </div>
        }
        @if (created() > 0) {
          <p class="created" role="status">
            {{ created() === 1 ? 'Se creó 1 casa' : 'Se crearon ' + created() + ' casas' }}. Entrá a cada una para completar su ficha.
          </p>
        }
        <ul class="grid">
          @for (unit of visible(); track unit.id) {
            <li><app-house-card [unit]="unit" [owner]="store.isOwner()" /></li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .houses {
      display: grid;
      gap: var(--ov-space-6);
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .intro .ov-lead {
      max-width: var(--ov-reading-max);
      margin-top: var(--ov-space-2);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .block {
      display: block;
      margin-top: var(--ov-space-1);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-5);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr));
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .check {
      display: flex;
      align-items: flex-start;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      cursor: pointer;
    }

    .check input {
      width: 1.125rem;
      height: 1.125rem;
      margin-top: 0.2rem;
      accent-color: var(--ov-accent);
    }

    .preview {
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      font-size: var(--ov-text-sm);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .filters {
      display: inline-flex;
      flex-wrap: wrap;
      justify-self: start;
      gap: var(--ov-space-1);
      padding: var(--ov-space-1);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .filter {
      min-height: 2.25rem;
      padding: 0 var(--ov-space-4);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      cursor: pointer;
    }

    .filter.active {
      background: var(--ov-surface);
      color: var(--ov-ink);
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
    }

    .filter:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .count {
      margin-left: var(--ov-space-1);
      color: var(--ov-ink-muted);
    }

    .created {
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 16rem), 1fr));
      gap: var(--ov-space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }
  `,
})
export class HousesComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  private readonly developments = inject(DevelopmentsService);
  private readonly nav = inject(OwnerNavStore);
  private readonly field = inject(FieldHouseStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly units = signal<Unit[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly created = signal(0);
  protected readonly filter = signal<Filter>(
    (['ALL', ...SALES_STATUS_OPTIONS] as Filter[]).find(option => option === inject(ActivatedRoute).snapshot.queryParamMap.get('estado')) ?? 'ALL');
  protected readonly filters: Filter[] = ['ALL', ...SALES_STATUS_OPTIONS];

  /** El filtro vive en la dirección (`?estado=`): entrar a una casa y volver lo conserva. */
  protected chooseFilter(filter: Filter): void {
    this.filter.set(filter);
    replaceQuery(this.router, this.route, { estado: filter === 'ALL' ? null : filter });
  }

  protected readonly form = this.formBuilder.nonNullable.group({
    prefix: ['Casa', [Validators.required, Validators.maxLength(40)]],
    firstNumber: [1, [Validators.required, Validators.min(0), Validators.max(9999)]],
    count: [10, [Validators.required, Validators.min(1), Validators.max(200)]],
    withStandardStages: [true],
  });

  private readonly formValue = signal(this.form.getRawValue());

  protected readonly preview = computed(() => {
    const { prefix, firstNumber, count } = this.formValue();
    return prefix.trim() && Number.isInteger(firstNumber) && count >= 1 && count <= 200
      ? batchNames(prefix, firstNumber, count)
      : [];
  });

  protected readonly visible = computed(() => {
    const filter = this.filter();
    return filter === 'ALL' ? this.units() : this.units().filter(unit => unit.salesStatus === filter);
  });

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.formValue.set(this.form.getRawValue()));
  }

  private get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    // "Agregar casas" del encabezado del desarrollo llega con ?nueva=1: el alta abierta. Se
    // escucha la dirección (no sólo al entrar) porque el botón sirve también estando en Casas,
    // y el parámetro se saca al usarlo: recargar no reabre el alta.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      if (params.has('nueva')) {
        if (this.store.isOwner()) {
          this.adding.set(true);
        }
        clearQuery(this.router, this.route, 'nueva');
      }
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.developments.units(this.developmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: units => {
        this.units.set(sortHouses(units));
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudieron cargar las casas.'));
      },
    });
  }

  protected startAdding(): void {
    const prefix = this.form.controls.prefix.value || 'Casa';
    this.form.patchValue({ firstNumber: nextHouseNumber(this.units(), prefix) });
    this.formError.set(null);
    this.adding.set(true);
  }

  protected create(): void {
    const value = this.form.getRawValue();
    if (this.form.invalid || !value.prefix.trim()) {
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    this.developments.createUnits(this.developmentId, {
      prefix: value.prefix.trim(),
      firstNumber: value.firstNumber,
      count: value.count,
      withStandardStages: value.withStandardStages,
    }).subscribe({
      next: houses => {
        this.saving.set(false);
        this.adding.set(false);
        this.created.set(houses.length);
        this.chooseFilter('ALL');
        this.load();
        this.store.reload();
        // Las casas nuevas van también al menú lateral y, si hay un encargado de todo, a su Hoy.
        this.nav.reloadUnits(this.developmentId);
        this.field.invalidate();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(errorMessageOf(error, 'No se pudieron crear las casas.'));
      },
    });
  }

  protected filterLabel(filter: Filter): string {
    return filter === 'ALL' ? 'Todas' : SALES_STATUS_LABELS[filter];
  }

  protected countOf(filter: Filter): number {
    return filter === 'ALL' ? this.units().length : this.units().filter(unit => unit.salesStatus === filter).length;
  }
}
