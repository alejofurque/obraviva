import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { Unit } from '../../core/api/api.models';
import { UnitsService } from '../../core/api/units.service';
import { OwnerNavStore } from '../../core/session/owner-nav.store';
import { FieldHouseStore } from '../field/field-house.store';
import { ComparisonsSummaryComponent } from './comparisons-summary.component';
import { HouseFrontComponent } from './house-front.component';
import { SalesGalleryComponent } from './sales-gallery.component';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { DocumentsManagerComponent } from './documents-manager.component';
import { HouseStore } from './house.store';
import { optionalNumber, optionalText, parseFeatures, salesSheetOf } from './unit-sheet';
import { ORIENTATIONS } from './work-labels';
import { WorkStore } from './work.store';
import { AmountInputDirective } from './amount-input.directive';

const MAX_FEATURES = 12;

/**
 * Ficha y galería de una casa: cómo se presenta al comprador. A la
 * izquierda, los datos del modelo (nombre, superficies, distribución, orientación, descripción y
 * terminaciones), la galería de venta y los planos y documentos; a la derecha, las imágenes
 * principales (portada, render del frente, foto de hoy) y proyecto y realidad.
 *
 * <p>El precio no está acá: es interno y vive en Venta. La ficha se guarda completa y
 * conserva el precio que la casa ya tiene.
 */
@Component({
  selector: 'app-house-sheet',
  imports: [AmountInputDirective, ReactiveFormsModule, RouterLink, DocumentsManagerComponent, HouseFrontComponent, SalesGalleryComponent, ComparisonsSummaryComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page sheet-page">
      <header class="intro">
        <div>
          <h2>Ficha y galería</h2>
          <p class="ov-lead">Lo que ve el comprador de esta casa. Lo que dejes vacío no se muestra.</p>
        </div>
        @if (house.unit(); as unit) {
          <a class="ov-btn ov-btn--secondary" [routerLink]="['/mi-propiedad', unit.id]" [queryParams]="{ obra: unit.developmentId }">
            Ver como el comprador
          </a>
        }
      </header>

      <div class="columns">
      <div class="main">
      <form [formGroup]="form" (ngSubmit)="save()" class="sections" novalidate>
        <section class="ov-card block" aria-labelledby="identity-title">
          <h3 id="identity-title">Datos del modelo</h3>
          <div class="grid">
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-code">Nombre</label>
              <input id="house-code" class="ov-input" formControlName="code" maxlength="40" placeholder="Casa 7, Lote 14" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-model">Modelo</label>
              <input id="house-model" class="ov-input" formControlName="modelName" maxlength="80" placeholder="Por ejemplo: Modelo Aromo" />
            </div>
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="house-short">Frase corta</label>
              <input id="house-short" class="ov-input" formControlName="description" maxlength="300"
                     placeholder="Por ejemplo: Casa de dos plantas con galería y parrilla" />
            </div>
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="house-long">Descripción para el comprador</label>
              <textarea id="house-long" class="ov-input" formControlName="longDescription" maxlength="4000"
                        placeholder="Cómo se vive la casa: ambientes, luz, espacios exteriores."></textarea>
            </div>
          </div>
        </section>

        <section class="ov-card block" aria-labelledby="areas-title">
          <h3 id="areas-title">Superficies y distribución</h3>
          <div class="grid compact">
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-lot">Lote (m²)</label>
              <input id="house-lot" class="ov-input" appAmount formControlName="lotAreaM2" />
              @if (form.controls.lotAreaM2.hasError('amount')) {
                <p class="ov-error">Con punto de miles y coma decimal: 42.000,50</p>
              }
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-covered">Cubierta (m²)</label>
              <input id="house-covered" class="ov-input" appAmount formControlName="coveredAreaM2" />
              @if (form.controls.coveredAreaM2.hasError('amount')) {
                <p class="ov-error">Con punto de miles y coma decimal: 42.000,50</p>
              }
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-semi">Semicubierta (m²)</label>
              <input id="house-semi" class="ov-input" appAmount formControlName="semiCoveredAreaM2" />
              @if (form.controls.semiCoveredAreaM2.hasError('amount')) {
                <p class="ov-error">Con punto de miles y coma decimal: 42.000,50</p>
              }
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-rooms">Ambientes</label>
              <input id="house-rooms" class="ov-input" type="number" min="0" max="50" formControlName="rooms" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-bedrooms">Dormitorios</label>
              <input id="house-bedrooms" class="ov-input" type="number" min="0" max="50" formControlName="bedrooms" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-bathrooms">Baños</label>
              <input id="house-bathrooms" class="ov-input" type="number" min="0" max="50" formControlName="bathrooms" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-toilettes">Toilettes</label>
              <input id="house-toilettes" class="ov-input" type="number" min="0" max="50" formControlName="toilettes" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-floors">Plantas</label>
              <input id="house-floors" class="ov-input" type="number" min="1" max="10" formControlName="floors" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-parking">Cocheras</label>
              <input id="house-parking" class="ov-input" type="number" min="0" max="20" formControlName="parkingSpaces" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-orientation">Orientación</label>
              <select id="house-orientation" class="ov-input" formControlName="orientation">
                <option value="">Sin indicar</option>
                @for (orientation of orientations; track orientation) {
                  <option [value]="orientation">{{ orientation }}</option>
                }
              </select>
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="house-delivery">Entrega estimada</label>
              <input id="house-delivery" class="ov-input" type="month" formControlName="estimatedDelivery" />
            </div>
          </div>
        </section>

        <section class="ov-card block" aria-labelledby="includes-title">
          <h3 id="includes-title">Qué incluye y terminaciones</h3>
          <div class="grid">
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="house-features">Incluye</label>
              <input id="house-features" class="ov-input" formControlName="features"
                     placeholder="Separados por coma: galería con parrilla, cochera doble, losa radiante" />
              <p class="muted">Hasta {{ maxFeatures }} elementos.</p>
            </div>
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="house-finishes">Terminaciones</label>
              <textarea id="house-finishes" class="ov-input" formControlName="finishes" maxlength="4000"
                        placeholder="Pisos, aberturas, calefacción, grifería, mesadas."></textarea>
            </div>
          </div>
        </section>

        <div class="save-bar">
          @if (error(); as message) {
            <p class="ov-error" role="alert">{{ message }}</p>
          }
          @if (saved()) {
            <p class="saved" role="status">Ficha guardada.</p>
          }
          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || form.invalid">
            {{ saving() ? 'Guardando…' : 'Guardar ficha' }}
          </button>
        </div>
      </form>

      @if (house.unit(); as unit) {
        <app-sales-gallery [developmentId]="unit.developmentId" [unitId]="unit.id" [sectors]="sectors()"
                           [placesLink]="['/obras', unit.developmentId, 'casas', unit.id, 'recorridos']" (changed)="house.reload()" />

        <section class="ov-card block" aria-labelledby="documents-title">
          <div class="block-head">
            <h3 id="documents-title">Planos y documentos</h3>
            <a class="ov-btn ov-btn--secondary" [routerLink]="['planos']">Editar planos</a>
          </div>
          <p class="muted">
            Los planos llevan marcados los ambientes: el comprador los toca y ve cada medida. Los documentos (boleto,
            planos aprobados, memoria del modelo) los ve sólo quien compró esta casa.
          </p>
          <app-documents-manager [developmentId]="unit.developmentId" [unitId]="unit.id" />
        </section>
      }
      </div>

      <div class="side">
        <app-house-front />
        @if (house.unit(); as unit) {
          <app-comparisons-summary [developmentId]="unit.developmentId" [unitId]="unit.id"
                                   [link]="['/obras', unit.developmentId, 'casas', unit.id, 'historia', 'comparaciones']" />
        }
      </div>
      </div>
    </div>
  `,
  styles: `
    .sheet-page {
      display: grid;
      gap: var(--ov-space-5);
      padding-top: var(--ov-space-6);
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--ov-space-5);
    }

    .main {
      display: grid;
      flex: 3 1 34rem;
      gap: var(--ov-space-5);
      min-width: 0;
    }

    .side {
      display: grid;
      flex: 2 1 20rem;
      gap: var(--ov-space-5);
      min-width: 0;
    }

    .block-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .intro {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .intro .ov-lead {
      margin-top: var(--ov-space-2);
    }

    .sections {
      display: grid;
      gap: var(--ov-space-5);
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

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
      gap: var(--ov-space-4);
    }

    .grid.compact {
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 9.5rem), 1fr));
    }

    .wide {
      grid-column: 1 / -1;
    }

    .save-bar {
      position: sticky;
      bottom: 0;
      z-index: 1;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4) 0;
      background: var(--ov-canvas);
    }

    .saved {
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class HouseSheetComponent implements OnInit, LeaveGuarded {

  protected readonly house = inject(HouseStore);
  private readonly work = inject(WorkStore);
  private readonly units = inject(UnitsService);
  private readonly nav = inject(OwnerNavStore);
  private readonly field = inject(FieldHouseStore);
  private readonly formBuilder = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly orientations = ORIENTATIONS;
  protected readonly maxFeatures = MAX_FEATURES;
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Los lugares de la casa, para "Mover a fotos de obra". */
  protected readonly sectors = signal<string[]>([]);

  protected readonly form = this.formBuilder.group({
    code: ['', [Validators.required, Validators.maxLength(40)]],
    description: [''],
    modelName: [''],
    longDescription: [''],
    lotAreaM2: [null as number | null, Validators.min(0.01)],
    coveredAreaM2: [null as number | null, Validators.min(0.01)],
    semiCoveredAreaM2: [null as number | null, Validators.min(0.01)],
    rooms: [null as number | null, [Validators.min(0), Validators.max(50)]],
    bedrooms: [null as number | null, [Validators.min(0), Validators.max(50)]],
    bathrooms: [null as number | null, [Validators.min(0), Validators.max(50)]],
    toilettes: [null as number | null, [Validators.min(0), Validators.max(50)]],
    floors: [null as number | null, [Validators.min(1), Validators.max(10)]],
    parkingSpaces: [null as number | null, [Validators.min(0), Validators.max(20)]],
    orientation: [''],
    estimatedDelivery: [''],
    features: [''],
    finishes: [''],
  });

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.form.dirty ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    const unit = this.house.unit();
    if (unit) {
      this.fill(unit);
    }
    this.units.sectors(this.house.unitId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: sectors => this.sectors.set(sectors),
      // Sin lugares la galería funciona igual: "Mover a fotos de obra" pide definirlos.
      error: () => this.sectors.set([]),
    });
  }

  /**
   * Guarda en tres pasos, los que expone el servidor: nombre, ficha básica y ficha de venta.
   * Se encadenan para que un error corte antes de dejar la casa a medias con datos viejos.
   */
  protected save(): void {
    const unit = this.house.unit();
    const value = this.form.getRawValue();
    if (!unit || this.form.invalid || !(value.code ?? '').trim()) {
      return;
    }
    const features = parseFeatures(value.features ?? '');
    if (features.length > MAX_FEATURES) {
      this.error.set(`Podés cargar hasta ${MAX_FEATURES} elementos en "Incluye".`);
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);

    this.units.rename(unit.id, (value.code ?? '').trim(), optionalText(value.description)).pipe(
      switchMap(() => this.units.updateDetails(unit.id, {
        areaM2: optionalNumber(value.coveredAreaM2),
        bedrooms: optionalNumber(value.bedrooms),
        bathrooms: optionalNumber(value.bathrooms),
        features,
        estimatedDelivery: value.estimatedDelivery || null,
      })),
      switchMap(() => this.units.updateSalesSheet(unit.id, salesSheetOf(unit, {
        modelName: optionalText(value.modelName),
        lotAreaM2: optionalNumber(value.lotAreaM2),
        coveredAreaM2: optionalNumber(value.coveredAreaM2),
        semiCoveredAreaM2: optionalNumber(value.semiCoveredAreaM2),
        rooms: optionalNumber(value.rooms),
        toilettes: optionalNumber(value.toilettes),
        floors: optionalNumber(value.floors),
        parkingSpaces: optionalNumber(value.parkingSpaces),
        orientation: optionalText(value.orientation),
        longDescription: optionalText(value.longDescription),
        finishes: optionalText(value.finishes),
      }))),
    ).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.markAsPristine();
        this.house.reload();
        this.work.reload();
        // El código de la casa también está en el menú lateral y en Hoy.
        this.nav.reloadUnits(unit.developmentId);
        this.field.invalidate();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo guardar la ficha.'));
      },
    });
  }

  private fill(unit: Unit): void {
    this.form.setValue({
      code: unit.code,
      description: unit.description ?? '',
      modelName: unit.modelName ?? '',
      longDescription: unit.longDescription ?? '',
      lotAreaM2: unit.lotAreaM2,
      coveredAreaM2: unit.coveredAreaM2 ?? unit.areaM2,
      semiCoveredAreaM2: unit.semiCoveredAreaM2,
      rooms: unit.rooms,
      bedrooms: unit.bedrooms,
      bathrooms: unit.bathrooms,
      toilettes: unit.toilettes,
      floors: unit.floors,
      parkingSpaces: unit.parkingSpaces,
      orientation: unit.orientation ?? '',
      estimatedDelivery: unit.estimatedDelivery ?? '',
      features: unit.features.join(', '),
      finishes: unit.finishes ?? '',
    });
  }
}
