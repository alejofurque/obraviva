import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, forkJoin, switchMap } from 'rxjs';
import { DevelopmentTeam, OrganizationMember, TeamPerson, Unit } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { FieldHouseStore } from '../field/field-house.store';
import { OrganizationsService } from '../../core/api/organizations.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { InitialPasswordComponent } from '../../shared/ui/initial-password.component';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { WorkStore } from './work.store';

/**
 * Equipo de la obra.
 *
 * <p>Un encargado trabaja en las obras que el dueño le asigna, y no ve las demás. Desde
 * acá se asigna a esta obra a alguien que ya es encargado de la empresa, o se suma a
 * alguien nuevo (que queda asignado a esta obra). Abajo, la empresa completa: contraseña
 * nueva y quitar de la empresa, que le saca todas sus obras.
 */
@Component({
  selector: 'app-work-team',
  imports: [NgTemplateOutlet, ReactiveFormsModule, InitialPasswordComponent, LoadingStateComponent, ErrorStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow team">
      <header class="intro">
        <h2>Equipo</h2>
        <p class="ov-lead">
          Cada encargado trabaja en todo el desarrollo o en casas puntuales, y la misma persona puede estar en varias.
          Publica avances, audios y recorridos 3D de lo suyo desde el celular; no ve el gasto, la venta ni a los compradores.
        </p>
      </header>

      @if (password(); as created) {
        <app-initial-password [password]="created.value" [person]="created.person" (dismissed)="password.set(null)" />
      }

      @if (actionError(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }

      @if (loading()) {
        <app-loading-state label="Cargando el equipo…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (team(); as current) {
        <section aria-labelledby="assigned-title">
          <h3 id="assigned-title">Encargados de este desarrollo</h3>
          @if (current.assigned.length === 0) {
            <p class="empty">Nadie asignado todavía. Sin encargado, los avances y recorridos los cargás vos.</p>
          } @else {
            <ul class="people">
              @for (person of current.assigned; track person.userId) {
                <li class="person">
                  <div>
                    <p class="name">{{ person.fullName ?? person.email }}</p>
                    <p class="muted">{{ person.email }}</p>
                    <p class="scope">{{ scopeLabel(person) }}</p>
                  </div>
                  @if (editingFor() === person.userId) {
                    <div class="scope-editor">
                      <ng-container *ngTemplateOutlet="scopePicker" />
                      <div class="actions">
                        <button class="ov-btn ov-btn--primary" type="button" [disabled]="busy() || !scopeValid()" (click)="saveScope(person)">Guardar casas</button>
                        <button class="ov-link" type="button" (click)="editingFor.set(null)">Cancelar</button>
                      </div>
                    </div>
                  } @else {
                    <div class="actions">
                      <button class="ov-btn ov-btn--secondary" type="button" [disabled]="busy()" (click)="editScope(person)">Cambiar casas</button>
                      <button class="ov-link" type="button" [disabled]="busy()" (click)="unassign(person)">Quitar</button>
                    </div>
                  }
                </li>
              }
            </ul>
          }
        </section>

        <section class="ov-card panel" aria-labelledby="add-title">
          <h3 id="add-title">Sumar un encargado</h3>
          @if (editingFor() === null) {
            <ng-container *ngTemplateOutlet="scopePicker" />
          }
          @if (current.available.length > 0) {
            <div class="assign">
              <div class="ov-field">
                <label class="ov-eyebrow" for="available">De la empresa</label>
                <select id="available" class="ov-input" #available>
                  @for (person of current.available; track person.userId) {
                    <option [value]="person.userId">{{ person.fullName ?? person.email }}</option>
                  }
                </select>
              </div>
              <button class="ov-btn ov-btn--primary" type="button" [disabled]="busy() || !scopeValid()" (click)="assign(available.value)">
                Asignar
              </button>
            </div>
            <p class="or">o una persona nueva</p>
          }
          <form [formGroup]="form" (ngSubmit)="addNew()" class="form" novalidate>
            <div class="ov-field">
              <label class="ov-eyebrow" for="member-email">Correo</label>
              <input id="member-email" class="ov-input" type="email" formControlName="email" autocomplete="off" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="member-name">Nombre</label>
              <input id="member-name" class="ov-input" formControlName="fullName" aria-describedby="member-hint" />
            </div>
            <button class="ov-btn ov-btn--secondary" type="submit" [disabled]="busy() || form.invalid || !scopeValid()">
              {{ busy() ? 'Sumando…' : 'Sumar y asignar' }}
            </button>
          </form>
          <p id="member-hint" class="muted">
            Si la persona todavía no tiene cuenta, se crea con el nombre y te mostramos una contraseña inicial.
          </p>
        </section>

        <ng-template #scopePicker>
          <fieldset class="picker">
            <legend class="ov-eyebrow">Dónde trabaja</legend>
            <label class="option">
              <input type="radio" name="scope" [checked]="scopeAll()" (change)="scopeAll.set(true)" />
              Todo el desarrollo, incluidas las obras comunes
            </label>
            <label class="option">
              <input type="radio" name="scope" [checked]="!scopeAll()" (change)="scopeAll.set(false)" />
              Casas puntuales
            </label>
            @if (!scopeAll()) {
              <div class="houses">
                @for (house of units(); track house.id) {
                  <label class="house">
                    <input type="checkbox" [checked]="scopeHouses().has(house.id)" (change)="toggleHouse(house.id)" />
                    {{ house.code }}
                  </label>
                }
              </div>
              @if (!scopeValid()) {
                <p class="muted">Elegí al menos una casa.</p>
              }
            }
          </fieldset>
        </ng-template>

        <details class="ov-disclosure company">
          <summary>Personas de la empresa ({{ members().length }})</summary>
          <p class="muted">Quitar a alguien de la empresa le saca el acceso a todas sus obras.</p>
          <ul class="people">
            @for (member of members(); track member.id) {
              <li class="person">
                <div>
                  <p class="name">{{ member.fullName ?? member.email }}</p>
                  <p class="muted">{{ member.email }}</p>
                </div>
                <span class="ov-pill" [class.ov-pill--accent]="member.role === 'OWNER'">
                  {{ member.role === 'OWNER' ? 'Responsable' : 'Encargado' }}
                </span>
                @if (member.role === 'MEMBER') {
                  @if (confirming() === member.id) {
                    <div class="actions">
                      <button class="ov-btn ov-btn--primary" type="button" (click)="remove(member)">Quitar de la empresa</button>
                      <button class="ov-link" type="button" (click)="confirming.set(null)">Cancelar</button>
                    </div>
                  } @else {
                    <div class="actions">
                      <button class="ov-link" type="button" (click)="reset(member)">Nueva contraseña</button>
                      <button class="ov-link" type="button" (click)="confirming.set(member.id)">Quitar de la empresa</button>
                    </div>
                  }
                }
              </li>
            }
          </ul>
        </details>
      }
    </div>
  `,
  styles: `
    .scope {
      margin-top: var(--ov-space-1);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    .scope-editor {
      display: grid;
      gap: var(--ov-space-3);
      width: 100%;
    }

    .picker {
      display: grid;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      border: 0;
    }

    .picker legend {
      margin-bottom: var(--ov-space-2);
    }

    .option,
    .house {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      min-height: 2.75rem;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .option input,
    .house input {
      width: 1.125rem;
      height: 1.125rem;
      accent-color: var(--ov-accent);
    }

    .houses {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr));
      gap: var(--ov-space-2);
      padding: var(--ov-space-3) var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
    }

    .team {
      display: grid;
      gap: var(--ov-space-6);
      --ov-measure: 52rem;
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: grid;
      gap: var(--ov-space-2);
    }

    h3 {
      margin-bottom: var(--ov-space-3);
      font-size: var(--ov-text-lg);
    }

    .muted,
    .empty,
    .or {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .people {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .person {
      display: grid;
      grid-template-columns: 1fr auto;
      align-items: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) 0;
      border-bottom: 1px solid var(--ov-line);
    }

    .name {
      font-weight: var(--ov-weight-medium);
    }

    .actions {
      grid-column: 1 / -1;
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .panel {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-6);
    }

    .assign,
    .form {
      display: grid;
      gap: var(--ov-space-4);
      align-items: end;
    }

    .company summary {
      cursor: pointer;
      font-weight: var(--ov-weight-medium);
    }

    .company[open] summary {
      margin-bottom: var(--ov-space-2);
    }

    @media (min-width: 48rem) {
      .person {
        grid-template-columns: 1fr auto auto;
      }

      .actions {
        grid-column: auto;
      }

      .assign {
        grid-template-columns: 1fr auto;
      }

      .form {
        grid-template-columns: 1fr 1fr auto;
      }
    }
  `,
})
export class WorkTeamComponent implements OnInit {

  private readonly store = inject(WorkStore);
  private readonly developments = inject(DevelopmentsService);
  private readonly field = inject(FieldHouseStore);
  private readonly organizations = inject(OrganizationsService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly team = signal<DevelopmentTeam | null>(null);
  protected readonly members = signal<OrganizationMember[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirming = signal<string | null>(null);
  protected readonly password = signal<{ value: string; person: string } | null>(null);

  protected readonly units = signal<Unit[]>([]);
  protected readonly scopeAll = signal(true);
  protected readonly scopeHouses = signal<Set<string>>(new Set());
  /** A quién se le están cambiando las casas; nulo mientras se asigna a alguien nuevo. */
  protected readonly editingFor = signal<string | null>(null);
  protected readonly scopeValid = computed(() => this.scopeAll() || this.scopeHouses().size > 0);

  protected readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    fullName: ['', Validators.maxLength(200)],
  });

  private get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  private get organizationId(): string {
    return this.store.development()?.organizationId ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.fetch(() => this.loading.set(false), message => this.loadError.set(message));
  }

  protected assign(userId: string): void {
    if (!userId) {
      return;
    }
    this.run(this.developments.assignSupervisor(this.developmentId, userId, this.chosenHouses()), 'No se pudo asignar a la persona.');
  }

  protected scopeLabel(person: TeamPerson): string {
    if (person.allHouses) {
      return 'Todo el desarrollo';
    }
    const codes = this.units().filter(unit => person.unitIds.includes(unit.id)).map(unit => unit.code);
    return codes.length === 0 ? 'Sin casas' : codes.join(', ');
  }

  protected editScope(person: TeamPerson): void {
    this.scopeAll.set(person.allHouses);
    this.scopeHouses.set(new Set(person.unitIds));
    this.editingFor.set(person.userId);
  }

  protected saveScope(person: TeamPerson): void {
    this.editingFor.set(null);
    this.run(this.developments.changeSupervisorHouses(this.developmentId, person.userId, this.chosenHouses()),
      'No se pudieron cambiar las casas.');
  }

  protected toggleHouse(unitId: string): void {
    this.scopeHouses.update(houses => {
      const next = new Set(houses);
      if (next.has(unitId)) {
        next.delete(unitId);
      } else {
        next.add(unitId);
      }
      return next;
    });
  }

  /** Vacío es todo el desarrollo; si no, las casas marcadas en orden. */
  private chosenHouses(): string[] {
    return this.scopeAll() ? [] : this.units().filter(unit => this.scopeHouses().has(unit.id)).map(unit => unit.id);
  }

  protected unassign(person: TeamPerson): void {
    this.run(this.developments.unassignSupervisor(this.developmentId, person.userId), 'No se pudo quitar de la obra.');
  }

  /** Suma a la empresa y asigna a esta obra en un paso: nadie entra a la empresa sin una obra. */
  protected addNew(): void {
    const { email, fullName } = this.form.getRawValue();
    this.busy.set(true);
    this.actionError.set(null);
    this.organizations.addMember(this.organizationId, email.trim(), fullName.trim() || null)
      .pipe(switchMap(added => {
        if (added.initialPassword) {
          this.password.set({ value: added.initialPassword, person: added.fullName ?? added.email ?? 'la persona' });
        }
        return this.developments.assignSupervisor(this.developmentId, added.userId, this.chosenHouses());
      }))
      .subscribe({
        next: () => {
          this.form.reset();
          this.refresh();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.actionError.set(errorMessageOf(error, 'No se pudo sumar a la persona.'));
          this.refresh();
        },
      });
  }

  protected reset(member: OrganizationMember): void {
    this.actionError.set(null);
    this.organizations.resetMemberPassword(this.organizationId, member.id).subscribe({
      // El reinicio de un encargado siempre genera una: el servidor la devuelve una vez.
      next: response => this.password.set(response.initialPassword
        ? { value: response.initialPassword, person: member.fullName ?? member.email ?? 'la persona' }
        : null),
      error: (error: unknown) => this.actionError.set(errorMessageOf(error, 'No se pudo generar una contraseña nueva.')),
    });
  }

  protected remove(member: OrganizationMember): void {
    this.confirming.set(null);
    this.run(this.organizations.removeMember(this.organizationId, member.id), 'No se pudo quitar a la persona.');
  }

  private run(request: Observable<unknown>, fallback: string): void {
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: () => {
        // Las casas asignadas cambian lo que ve Hoy, si quien está adentro también es encargado.
        this.field.invalidate();
        this.refresh();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.actionError.set(errorMessageOf(error, fallback));
      },
    });
  }

  /** Recarga sin volver al estado de carga: la contraseña recién creada queda a la vista. */
  private refresh(): void {
    this.fetch(() => this.busy.set(false), message => this.actionError.set(message));
  }

  private fetch(done: () => void, fail: (message: string) => void): void {
    forkJoin({
      team: this.developments.team(this.developmentId),
      members: this.organizations.members(this.organizationId),
      units: this.developments.units(this.developmentId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ team, members, units }) => {
          this.team.set(team);
          this.members.set(members);
          this.units.set([...units].sort((a, b) => a.code.localeCompare(b.code, 'es', { numeric: true })));
          done();
        },
        error: (error: unknown) => {
          done();
          fail(errorMessageOf(error, 'No se pudo cargar el equipo.'));
        },
      });
  }
}
