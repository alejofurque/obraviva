import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { UnitMember, UnitSalesStatus } from '../../core/api/api.models';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { InitialPasswordComponent } from '../../shared/ui/initial-password.component';
import { todayLocal } from '../../shared/time/local-date';
import { CURRENCY } from '../../shared/money/money';
import { concat, last } from 'rxjs';
import { PublicLinksComponent } from './public-links.component';
import { HouseStore } from './house.store';
import { optionalNumber, salesSheetOf } from './unit-sheet';
import { SALES_STATUS_LABELS, SALES_STATUS_OPTIONS } from './work-labels';
import { WorkStore } from './work.store';
import { AmountInputDirective } from './amount-input.directive';

/** El mismo mínimo que valida el servidor para una contraseña elegida. */
const MIN_PASSWORD_LENGTH = 8;

/** Lo que dice cada estado, sin prometer nada que el sistema no haga. */
export const SALES_STATUS_HELP: Record<UnitSalesStatus, string> = {
  AVAILABLE: 'Sin reserva ni venta. Es interno: no lo ve nadie fuera de la empresa.',
  RESERVED: 'Está reservada. Podés agregar al comprador para que empiece a seguir la obra.',
  SOLD: 'Está vendida. Su comprador sigue la obra desde "Mi casa".',
};

/**
 * Venta de una casa: el estado comercial con el día de la reserva o
 * la venta y el precio de lista (internos, nunca los ve el comprador), con un solo "Guardar";
 * quién la compró; y sus enlaces públicos. Vincular un comprador a una casa disponible la
 * marca vendida. Es una fecha, no un historial: nada de montos de seña ni CRM.
 */
@Component({
  selector: 'app-house-sales',
  imports: [AmountInputDirective, ReactiveFormsModule, InitialPasswordComponent, PublicLinksComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page sales-page">
      @if (house.unit(); as unit) {
        <header>
          <h2>Venta</h2>
          <p class="ov-lead">Estado comercial, quién sigue esta casa y con qué enlaces. El estado y el precio son internos: el comprador y los enlaces nunca los muestran.</p>
        </header>

        <section class="ov-card block status-block" aria-labelledby="status-title">
          <form class="status-row" [formGroup]="saleForm" (ngSubmit)="saveSale()" novalidate>
            <div class="ov-field">
              <span id="status-title" class="ov-eyebrow">Estado</span>
              <div class="segmented" role="radiogroup" aria-labelledby="status-title">
                @for (option of salesOptions; track option) {
                  <button type="button" role="radio" class="option ov-hit" [class.active]="draftStatus() === option"
                          [attr.aria-checked]="draftStatus() === option" [disabled]="savingSale()"
                          (click)="chooseStatus(option)">{{ salesLabels[option] }}</button>
                }
              </div>
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="price-amount">Precio de lista (US$)</label>
              <div class="price">
                <span class="currency" aria-hidden="true">US$</span>
                <input id="price-amount" class="ov-input" appAmount formControlName="amount" placeholder="Sin precio" />
              </div>
              @if (saleForm.controls.amount.hasError('amount')) {
                <p class="ov-error">Con punto de miles y coma decimal: 42.000,50</p>
              }
            </div>
            @if (draftStatus() !== 'AVAILABLE') {
              <div class="ov-field">
                <label class="ov-eyebrow" for="status-on">{{ draftStatus() === 'RESERVED' ? 'Fecha de la reserva' : 'Fecha de la venta' }}</label>
                <input id="status-on" class="ov-input" type="date" [max]="today" formControlName="on" />
                @if (!saleForm.controls.on.value) {
                  <span class="muted">Sin fecha cargada.</span>
                }
              </div>
            }
            <p class="help">{{ help() }}</p>
            <button class="ov-btn ov-btn--primary" type="submit" [disabled]="savingSale() || saleForm.invalid">
              {{ savingSale() ? 'Guardando…' : 'Guardar' }}
            </button>
          </form>
          @if (saleError(); as message) {
            <p class="ov-error" role="alert">{{ message }}</p>
          }
          @if (saleSaved()) {
            <p class="status" role="status">Guardado.</p>
          }
        </section>

        <div class="columns">
        <section class="ov-card block" aria-labelledby="buyers-title">
          <h3 id="buyers-title">Compradores</h3>
          <p class="muted">Entran con su cuenta a "Mi casa": ven la obra, sus documentos y pueden crear enlaces.</p>

          @if (password(); as created) {
            <app-initial-password [password]="created.value" [person]="created.person" (dismissed)="password.set(null)" />
          }
          @if (membersError(); as message) {
            <p class="ov-error" role="alert">{{ message }}</p>
          }
          @if (passwordStatus(); as status) {
            <p class="status" role="status">{{ status }}</p>
          }

          @if (membersLoading()) {
            <p class="muted" role="status">Cargando compradores…</p>
          } @else if (membersFailed()) {
            <button type="button" class="ov-link" (click)="loadMembers()">Reintentar</button>
          } @else if (members().length === 0) {
            <p class="muted">Nadie vinculado todavía.</p>
          } @else {
            <ul class="members">
              @for (member of members(); track member.id) {
                <li class="member">
                  <div>
                    <p class="member-name">{{ member.fullName ?? member.email }}</p>
                    <p class="muted">{{ member.email }}</p>
                  </div>
                  @if (confirmingRemoval() === member.id) {
                    <div class="actions">
                      <button class="ov-btn ov-btn--primary" type="button" (click)="removeMember(member)">Quitar acceso</button>
                      <button class="ov-link" type="button" (click)="confirmingRemoval.set(null)">Cancelar</button>
                    </div>
                  } @else if (settingPasswordFor() !== member.id) {
                    <div class="actions">
                      <button class="ov-link" type="button" (click)="startNewPassword(member)">Nueva contraseña</button>
                      <button class="ov-link" type="button" (click)="confirmingRemoval.set(member.id)">Quitar</button>
                    </div>
                  }
                  @if (settingPasswordFor() === member.id) {
                    <form class="new-password" (submit)="$event.preventDefault(); saveNewPassword(member)" novalidate>
                      <div class="ov-field">
                        <label class="ov-eyebrow" [for]="'new-password-' + member.id">Nueva contraseña</label>
                        <div class="secret">
                          <input class="ov-input" [id]="'new-password-' + member.id" name="newPassword"
                                 [type]="showNewPassword() ? 'text' : 'password'" autocomplete="new-password"
                                 [formControl]="newPassword" />
                          <button class="ov-link" type="button" [attr.aria-pressed]="showNewPassword()"
                                  (click)="showNewPassword.set(!showNewPassword())">{{ showNewPassword() ? 'Ocultar' : 'Mostrar' }}</button>
                        </div>
                        <p class="muted">Si la dejás vacía, te mostramos una contraseña generada.</p>
                      </div>
                      <div class="actions">
                        <button class="ov-btn ov-btn--primary" type="submit" [disabled]="savingPassword() || newPassword.invalid">
                          {{ savingPassword() ? 'Guardando…' : 'Guardar contraseña' }}
                        </button>
                        <button class="ov-link" type="button" (click)="cancelNewPassword()">Cancelar</button>
                      </div>
                    </form>
                  }
                </li>
              }
            </ul>
          }

          <form [formGroup]="buyerForm" (ngSubmit)="addBuyer()" class="buyer" novalidate>
            <div class="ov-field">
              <label class="ov-eyebrow" for="buyer-email">Correo del comprador</label>
              <input id="buyer-email" class="ov-input" type="email" formControlName="email" autocomplete="off" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="buyer-name">Nombre</label>
              <input id="buyer-name" class="ov-input" formControlName="fullName" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="buyer-password">Contraseña <span class="optional">(opcional)</span></label>
              <div class="secret">
                <input id="buyer-password" class="ov-input" [type]="showBuyerPassword() ? 'text' : 'password'"
                       formControlName="password" autocomplete="new-password" />
                <button class="ov-link" type="button" [attr.aria-pressed]="showBuyerPassword()"
                        (click)="showBuyerPassword.set(!showBuyerPassword())">{{ showBuyerPassword() ? 'Ocultar' : 'Mostrar' }}</button>
              </div>
            </div>
            <p class="muted wide">
              Si la persona no tiene cuenta, se crea con el nombre y esta contraseña, de al menos 8 caracteres. Si la dejás
              vacía, te mostramos una contraseña generada. Si ya tiene cuenta, su contraseña no cambia.
            </p>
            <div class="actions wide">
              <button class="ov-btn ov-btn--primary" type="submit" [disabled]="addingBuyer() || buyerForm.invalid">
                {{ addingBuyer() ? 'Vinculando…' : 'Vincular comprador' }}
              </button>
            </div>
          </form>
        </section>

          <app-public-links [unitId]="unit.id" [developmentId]="unit.developmentId" />
        </div>
      }
    </div>
  `,
  styles: `
    .sales-page {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    /* Estado, precio, fecha, la ayuda y Guardar en una fila que se parte en el teléfono. */
    .status-row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--ov-space-4) var(--ov-space-6);
    }

    .help {
      flex: 1 1 14rem;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-5);
    }

    .columns > * {
      flex: 1 1 26rem;
      min-width: 0;
    }

    header .ov-lead {
      margin-top: var(--ov-space-2);
    }

    .block {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-6);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .segmented {
      display: inline-flex;
      justify-self: start;
      padding: var(--ov-space-1);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
    }

    .option {
      min-height: 2.5rem;
      padding: 0 var(--ov-space-5);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: transparent;
      color: var(--ov-ink-muted);
      font: inherit;
      font-weight: var(--ov-weight-medium);
      cursor: pointer;
    }

    .option.active {
      background: var(--ov-surface);
      color: var(--ov-ink);
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
    }

    .option:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .price {
      display: flex;
      gap: var(--ov-space-2);
    }

    .price {
      align-items: center;
    }

    .currency {
      color: var(--ov-ink-muted);
      font-weight: var(--ov-weight-semibold);
    }

    .price input {
      width: 9rem;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .members {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .member {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-bottom: 1px solid var(--ov-line);
    }

    .member-name {
      font-weight: var(--ov-weight-medium);
    }

    .buyer {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .optional {
      color: var(--ov-ink-muted);
      font-weight: var(--ov-weight-regular);
    }

    /* El campo y su "Mostrar" en una línea. */
    .secret {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .secret .ov-input {
      flex: 1;
      min-width: 0;
    }

    .new-password {
      display: grid;
      gap: var(--ov-space-3);
      width: 100%;
    }

    .status {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    /* En el teléfono el estado ocupa el ancho y sus tres opciones se reparten. */
    @media (max-width: 40rem) {
      .status-row > .ov-field,
      .segmented {
        width: 100%;
      }

      .segmented {
        display: flex;
      }

      .option {
        flex: 1;
        padding: 0 var(--ov-space-2);
      }

      .columns > * {
        flex-basis: 100%;
      }
    }
  `,
})
export class HouseSalesComponent implements OnInit, LeaveGuarded {

  protected readonly house = inject(HouseStore);
  private readonly work = inject(WorkStore);
  private readonly units = inject(UnitsService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly salesOptions = SALES_STATUS_OPTIONS;
  protected readonly salesLabels = SALES_STATUS_LABELS;
  protected readonly today = todayLocal();
  protected readonly draftStatus = signal<UnitSalesStatus>('AVAILABLE');
  protected readonly help = computed(() => SALES_STATUS_HELP[this.draftStatus()]);
  protected readonly savingSale = signal(false);
  protected readonly saleError = signal<string | null>(null);
  protected readonly saleSaved = signal(false);
  protected readonly members = signal<UnitMember[]>([]);
  protected readonly membersLoading = signal(true);
  /** La lista no cargó: el error se ve arriba y no se dice "nadie vinculado". */
  protected readonly membersFailed = signal(false);
  protected readonly membersError = signal<string | null>(null);
  protected readonly addingBuyer = signal(false);
  protected readonly confirmingRemoval = signal<string | null>(null);
  protected readonly password = signal<{ value: string; person: string } | null>(null);

  protected readonly saleForm = this.formBuilder.nonNullable.group({
    amount: [null as number | null, Validators.min(1)],
    on: [''],
  });

  protected readonly buyerForm = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    fullName: ['', Validators.maxLength(200)],
    // Opcional; si se escribe, el mismo mínimo que valida el servidor.
    password: ['', Validators.minLength(MIN_PASSWORD_LENGTH)],
  });
  protected readonly showBuyerPassword = signal(false);

  /** "Nueva contraseña" en la fila de un comprador: escribir una o dejarla vacía para generar. */
  protected readonly settingPasswordFor = signal<string | null>(null);
  protected readonly newPassword = new FormControl('', { nonNullable: true, validators: Validators.minLength(MIN_PASSWORD_LENGTH) });
  protected readonly showNewPassword = signal(false);
  protected readonly savingPassword = signal(false);
  /** Aviso de que una contraseña elegida quedó guardada; la contraseña en sí no se muestra. */
  protected readonly passwordStatus = signal<string | null>(null);

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    const unit = this.house.unit();
    const statusChanged = !!unit && this.draftStatus() !== (unit.salesStatus ?? 'AVAILABLE');
    return this.saleForm.dirty || this.buyerForm.dirty || this.newPassword.dirty || statusChanged ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    const unit = this.house.unit();
    if (unit) {
      this.saleForm.setValue({ amount: unit.listPrice, on: unit.salesStatusOn ?? '' });
      this.draftStatus.set(unit.salesStatus ?? 'AVAILABLE');
    }
    this.loadMembers();
  }

  /** Al pasar de disponible a reservada o vendida, la fecha arranca en hoy. */
  protected chooseStatus(status: UnitSalesStatus): void {
    if (status !== 'AVAILABLE' && this.draftStatus() === 'AVAILABLE' && !this.saleForm.controls.on.value) {
      this.saleForm.controls.on.setValue(this.today);
    }
    this.draftStatus.set(status);
    this.saleSaved.set(false);
  }

  /**
   * Guarda lo que cambió: el estado con su día y el precio, que va en la ficha de venta (se
   * reemplaza completa, con todo lo que ya tiene). Sin cambios, no manda nada.
   */
  protected saveSale(): void {
    const unit = this.house.unit();
    if (!unit || this.saleForm.invalid) {
      return;
    }
    const { amount, on } = this.saleForm.getRawValue();
    const status = this.draftStatus();
    const day = status === 'AVAILABLE' ? null : on || null;
    const listPrice = optionalNumber(amount);
    const requests = [];
    if (status !== unit.salesStatus || day !== (unit.salesStatusOn ?? null)) {
      requests.push(this.units.changeSalesStatus(unit.id, status, day));
    }
    if (listPrice !== unit.listPrice || (listPrice !== null && unit.priceCurrency !== CURRENCY)) {
      requests.push(this.units.updateSalesSheet(unit.id, salesSheetOf(unit, {
        listPrice,
        priceCurrency: listPrice === null ? null : CURRENCY,
      })));
    }
    if (requests.length === 0) {
      this.saleSaved.set(true);
      return;
    }
    this.savingSale.set(true);
    this.saleError.set(null);
    this.saleSaved.set(false);
    concat(...requests).pipe(last()).subscribe({
      next: () => {
        this.savingSale.set(false);
        this.saleSaved.set(true);
        this.saleForm.markAsPristine();
        this.house.reload();
        this.work.reload();
      },
      error: (error: unknown) => {
        this.savingSale.set(false);
        this.saleError.set(errorMessageOf(error, 'No se pudo guardar.'));
      },
    });
  }

  protected addBuyer(): void {
    const unit = this.house.unit();
    const { email, fullName, password } = this.buyerForm.getRawValue();
    if (!unit) {
      return;
    }
    this.addingBuyer.set(true);
    this.membersError.set(null);
    this.passwordStatus.set(null);
    this.units.addMember(unit.id, email.trim(), fullName.trim() || null, password || null).subscribe({
      next: added => {
        this.addingBuyer.set(false);
        // La contraseña escrita no queda en el formulario ni se vuelve a mostrar.
        this.buyerForm.reset();
        this.showBuyerPassword.set(false);
        if (added.initialPassword) {
          this.password.set({ value: added.initialPassword, person: added.fullName ?? added.email ?? 'la persona' });
        }
        this.loadMembers();
        this.house.reload();
        this.work.reload();
      },
      error: (error: unknown) => {
        this.addingBuyer.set(false);
        this.membersError.set(errorMessageOf(error, 'No se pudo vincular al comprador.'));
      },
    });
  }

  protected startNewPassword(member: UnitMember): void {
    this.newPassword.reset('');
    this.showNewPassword.set(false);
    this.membersError.set(null);
    this.passwordStatus.set(null);
    this.settingPasswordFor.set(member.id);
  }

  protected cancelNewPassword(): void {
    this.newPassword.reset('');
    this.settingPasswordFor.set(null);
  }

  /**
   * Guarda la contraseña escrita o, vacía, pide una generada. La generada se muestra una
   * vez; la escrita no se vuelve a mostrar: sólo se avisa que quedó guardada.
   */
  protected saveNewPassword(member: UnitMember): void {
    if (this.newPassword.invalid) {
      return;
    }
    const chosen = this.newPassword.value || null;
    const person = member.fullName ?? member.email ?? 'la persona';
    this.savingPassword.set(true);
    this.membersError.set(null);
    this.units.resetMemberPassword(member.id, chosen).subscribe({
      next: response => {
        this.savingPassword.set(false);
        this.cancelNewPassword();
        if (response.initialPassword) {
          this.password.set({ value: response.initialPassword, person });
        } else {
          this.passwordStatus.set(`Listo: ${person} ya puede entrar con la contraseña nueva.`);
        }
      },
      error: (error: unknown) => {
        this.savingPassword.set(false);
        this.membersError.set(errorMessageOf(error, 'No se pudo cambiar la contraseña.'));
      },
    });
  }

  protected removeMember(member: UnitMember): void {
    this.confirmingRemoval.set(null);
    this.units.removeMember(member.id).subscribe({
      next: () => {
        this.loadMembers();
        this.house.reload();
      },
      error: (error: unknown) => this.membersError.set(errorMessageOf(error, 'No se pudo quitar el acceso.')),
    });
  }

  protected loadMembers(): void {
    this.membersLoading.set(this.members().length === 0);
    this.membersFailed.set(false);
    this.units.members(this.house.unitId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: members => {
        this.members.set(members);
        this.membersLoading.set(false);
        this.membersError.set(null);
      },
      error: (error: unknown) => {
        this.membersLoading.set(false);
        this.membersFailed.set(this.members().length === 0);
        this.membersError.set(errorMessageOf(error, 'No se pudieron cargar los compradores.'));
      },
    });
  }
}
