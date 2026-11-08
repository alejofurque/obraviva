import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Development, Organization } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { OrganizationsService } from '../../core/api/organizations.service';
import { UserContextService } from '../../core/session/user-context.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { clearQuery } from '../../core/routing/query-state';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { DEVELOPMENT_TYPE_LABELS } from './work-labels';
import { ImagePipe } from '../../shared/media/variant.pipe';

/**
 * Desarrollos de la persona: los que administra y los que sigue como encargada.
 *
 * <p>ObraViva se instala para un desarrollista: la empresa nace con la instalación y acá
 * no se crean empresas. Un desarrollo es un barrio o complejo de casas; se crea con nombre
 * y dirección, y el resto (datos, casas y etapas) se carga adentro. El encargado ve los
 * desarrollos a los que lo sumaron.
 */
@Component({
  selector: 'app-works-list',
  imports: [ImagePipe, RouterLink, ReactiveFormsModule, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page">
      <div class="head">
        <div>
          <p class="ov-eyebrow">Desarrollos</p>
          <h1 class="title">Tus desarrollos</h1>
        </div>
        @if (canCreate() && !creating()) {
          <button class="ov-btn ov-btn--primary" type="button" (click)="creating.set(true)">Nuevo desarrollo</button>
        }
      </div>

      @if (creating()) {
        <section class="ov-card panel" aria-labelledby="new-title">
          <h2 id="new-title">Nuevo desarrollo</h2>
          <p class="muted">Con el nombre alcanza para empezar. Después cargás sus datos, sus casas y sus etapas.</p>
          <form [formGroup]="form" (ngSubmit)="create()" class="form" novalidate>
            @if (ownedOrganizations().length > 1) {
              <div class="ov-field">
                <label class="ov-eyebrow" for="org-select">Empresa</label>
                <select id="org-select" class="ov-input" formControlName="organizationId">
                  @for (organization of ownedOrganizations(); track organization.id) {
                    <option [value]="organization.id">{{ organization.name }}</option>
                  }
                </select>
              </div>
            }
            <div class="ov-field">
              <label class="ov-eyebrow" for="name">Nombre del desarrollo</label>
              <input id="name" class="ov-input" formControlName="name" placeholder="Por ejemplo: Barrio Los Aromos" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="address">Dirección</label>
              <input id="address" class="ov-input" formControlName="address" />
            </div>
            @if (formError(); as message) {
              <p class="ov-error" role="alert">{{ message }}</p>
            }
            <div class="actions">
              <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || !formValid()">
                {{ saving() ? 'Creando…' : 'Crear desarrollo' }}
              </button>
              <button class="ov-link" type="button" (click)="cancel()">Cancelar</button>
            </div>
          </form>
        </section>
      }

      @if (loading()) {
        <app-loading-state label="Cargando tus desarrollos…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (developments().length === 0 && !creating()) {
        @if (canCreate()) {
          <app-empty-state
            title="Todavía no hay desarrollos"
            message="Creá tu primer desarrollo, cargá sus casas y seguí la obra de cada una hasta entregarla.">
            <button class="ov-btn ov-btn--primary" type="button" (click)="creating.set(true)">Crear desarrollo</button>
          </app-empty-state>
        } @else {
          <app-empty-state
            title="Todavía no hay desarrollos"
            message="Cuando el responsable te sume a un desarrollo, lo vas a ver acá." />
        }
      } @else {
        <ul class="works">
          @for (development of developments(); track development.id) {
            <li>
              <a class="ov-card work" [routerLink]="['/obras', development.id]">
                @if (development.coverUrl) {
                  <img class="work-cover" [src]="development | image: 'cover' : 'thumb'" [alt]="'Portada de ' + development.name" loading="lazy" />
                }
                <div class="work-head">
                  <p class="ov-eyebrow">{{ typeOf(development) }} · {{ placeOf(development) }}</p>
                  <span class="ov-pill" [class.ov-pill--accent]="development.role === 'OWNER'">
                    {{ development.role === 'OWNER' ? 'Responsable' : 'Encargado' }}
                  </span>
                </div>
                <p class="name">{{ development.name }}</p>
                <div class="progress-row">
                  <span class="percent ov-num">{{ development.overallProgressPercent }}%</span>
                  <span class="stage">
                    {{ development.unitCount === 1 ? '1 casa' : development.unitCount + ' casas' }}@if (development.completedUnitCount > 0) { · {{ development.completedUnitCount }} terminadas }
                  </span>
                </div>
                <div class="ov-progress" aria-hidden="true">
                  <span [style.width.%]="development.overallProgressPercent"></span>
                </div>
              </a>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .head {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
      margin-bottom: var(--ov-space-7);
    }

    .title {
      margin-top: var(--ov-space-3);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-4);
      margin-bottom: var(--ov-space-7);
      padding: var(--ov-space-6);
    }

    .form {
      display: grid;
      gap: var(--ov-space-4);
      max-width: 32rem;
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .works {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 22rem), 1fr));
      gap: var(--ov-space-5);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .work {
      display: grid;
      align-content: start;
      gap: var(--ov-space-3);
      overflow: hidden;
      height: 100%;
      padding: var(--ov-space-6);
      color: inherit;
      text-decoration: none;
      transition: border-color var(--ov-duration-fast) var(--ov-ease);
    }

    .work:hover {
      border-color: var(--ov-line-strong);
    }

    .work-cover {
      width: calc(100% + 2 * var(--ov-space-6));
      max-width: none;
      aspect-ratio: 16 / 9;
      margin: calc(-1 * var(--ov-space-6)) calc(-1 * var(--ov-space-6)) var(--ov-space-2);
      object-fit: cover;
      background: var(--ov-surface-muted);
    }

    .work-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .name {
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.015em;
    }

    .progress-row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
      margin-top: var(--ov-space-3);
    }

    .percent {
      font-size: var(--ov-text-2xl);
      font-weight: var(--ov-weight-semibold);
      line-height: 1;
    }

    .stage {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-align: right;
    }
  `,
})
export class WorksListComponent implements OnInit {

  private readonly developmentsService = inject(DevelopmentsService);
  private readonly organizationsService = inject(OrganizationsService);
  private readonly context = inject(UserContextService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly developments = signal<Development[]>([]);
  private readonly organizations = signal<Organization[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    organizationId: [''],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    address: ['', Validators.maxLength(300)],
  });

  /**
   * Organizaciones donde la persona es dueña: sólo ahí puede crear obras. Se deducen de
   * sus obras con rol de dueño, más las organizaciones que todavía no tienen ninguna.
   */
  protected readonly ownedOrganizations = computed(() => {
    const memberOnly = new Set(
      this.developments().filter(d => d.role === 'MEMBER').map(d => d.organizationId));
    return this.organizations().filter(organization => !memberOnly.has(organization.id));
  });

  /** Crea obras quien es dueño de la empresa. */
  protected readonly canCreate = computed(() => this.ownedOrganizations().length > 0);

  ngOnInit(): void {
    // "+ Nuevo desarrollo" del menú lateral llega con ?nuevo=1: el formulario abierto. Se
    // escucha la dirección porque el menú sigue a la vista, y el parámetro se saca al usarlo.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      if (params.has('nuevo')) {
        this.creating.set(true);
        clearQuery(this.router, this.route, 'nuevo');
      }
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      developments: this.developmentsService.list(),
      organizations: this.organizationsService.list(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ developments, organizations }) => {
          this.developments.set(developments);
          this.organizations.set(organizations);
          this.form.controls.organizationId.setValue(this.ownedOrganizations()[0]?.id ?? '');
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar tus desarrollos.'));
        },
      });
  }

  protected formValid(): boolean {
    const { organizationId, name } = this.form.getRawValue();
    return this.form.valid && name.trim().length > 0 && organizationId.length > 0;
  }

  protected create(): void {
    if (!this.formValid()) {
      return;
    }
    this.saving.set(true);
    this.formError.set(null);

    const { organizationId, name, address } = this.form.getRawValue();
    // Un desarrollo nace vacío y se le cargan casas: siempre con unidades.
    this.developmentsService
      .create({ organizationId, name: name.trim(), address: address.trim() || undefined, kind: 'MULTI_UNIT' })
      .subscribe({
        next: development => {
          this.saving.set(false);
          this.context.refresh().subscribe({ error: () => {} });
          this.router.navigate(['/obras', development.id]);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.formError.set(errorMessageOf(error, 'No se pudo crear el desarrollo.'));
        },
      });
  }

  protected cancel(): void {
    this.creating.set(false);
    this.formError.set(null);
    this.form.reset({ organizationId: this.ownedOrganizations()[0]?.id ?? '' });
  }

  protected typeOf(development: Development): string {
    return development.developmentType ? DEVELOPMENT_TYPE_LABELS[development.developmentType] : 'Desarrollo de casas';
  }

  protected placeOf(development: Development): string {
    return development.locality ?? development.address ?? 'Sin ubicación';
  }
}
