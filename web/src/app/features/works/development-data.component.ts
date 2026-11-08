import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Development, DevelopmentType } from '../../core/api/api.models';
import { DevelopmentsService } from '../../core/api/developments.service';
import { UserContextService } from '../../core/session/user-context.service';
import { FieldHouseStore } from '../field/field-house.store';
import { errorMessageOf } from '../../shared/http/error-message';
import { LeaveGuarded, UNSAVED_CHANGES, warnOnUnload } from '../../shared/ui/leave-guard';
import { DocumentsManagerComponent } from './documents-manager.component';
import { optionalNumber, optionalText, parseFeatures } from './unit-sheet';
import { DEVELOPMENT_TYPE_LABELS, DEVELOPMENT_TYPE_OPTIONS } from './work-labels';
import { WorkStore } from './work.store';
import { ImagePipe } from '../../shared/media/variant.pipe';
import { AmountInputDirective } from './amount-input.directive';

/**
 * Datos del desarrollo: lo que un comprador quiere saber del barrio antes de mirar su
 * casa. Ubicación, tipo, descripción, amenities y servicios, quién lo proyecta y lo
 * construye, la portada y los documentos que son para todos los compradores.
 */
@Component({
  selector: 'app-development-data',
  imports: [AmountInputDirective, ImagePipe, ReactiveFormsModule, DocumentsManagerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow data-page">
      <header>
        <h2>Datos del desarrollo</h2>
        <p class="ov-lead">Lo que ve el comprador sobre el barrio, arriba de la ficha de su casa. Los datos que dejes vacíos no se muestran.</p>
      </header>

      <form [formGroup]="form" (ngSubmit)="save()" class="sections" novalidate>
        <section class="ov-card block" aria-labelledby="identity-title">
          <h3 id="identity-title">Identidad y ubicación</h3>
          <div class="grid">
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="dev-name">Nombre</label>
              <input id="dev-name" class="ov-input" formControlName="name" maxlength="200" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-type">Tipo</label>
              <select id="dev-type" class="ov-input" formControlName="developmentType">
                <option value="">Sin indicar</option>
                @for (type of types; track type) {
                  <option [value]="type">{{ typeLabels[type] }}</option>
                }
              </select>
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-address">Dirección</label>
              <input id="dev-address" class="ov-input" formControlName="address" maxlength="300" placeholder="Calle y número, o ruta y kilómetro" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-locality">Localidad</label>
              <input id="dev-locality" class="ov-input" formControlName="locality" maxlength="120" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-province">Provincia</label>
              <input id="dev-province" class="ov-input" formControlName="province" maxlength="80" />
            </div>
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="dev-description">Descripción</label>
              <textarea id="dev-description" class="ov-input" formControlName="description" maxlength="4000"
                        placeholder="Cómo es el barrio, a quién está pensado, qué lo hace distinto."></textarea>
            </div>
          </div>
        </section>

        <section class="ov-card block" aria-labelledby="project-title">
          <h3 id="project-title">Proyecto</h3>
          <div class="grid">
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-land">Superficie total (m²)</label>
              <input id="dev-land" class="ov-input" appAmount formControlName="landAreaM2" />
              @if (form.controls.landAreaM2.hasError('amount')) {
                <p class="ov-error">Con punto de miles y coma decimal: 42.000,50</p>
              }
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-started">Inicio de obra</label>
              <input id="dev-started" class="ov-input" type="date" formControlName="startedOn" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-delivery">Entrega estimada</label>
              <input id="dev-delivery" class="ov-input" type="month" formControlName="estimatedDelivery" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-permit">Expediente o permiso</label>
              <input id="dev-permit" class="ov-input" formControlName="permitNumber" maxlength="80" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-architect">Proyecto y dirección</label>
              <input id="dev-architect" class="ov-input" formControlName="architect" maxlength="160" placeholder="Estudio o profesional" />
            </div>
            <div class="ov-field">
              <label class="ov-eyebrow" for="dev-builder">Constructora</label>
              <input id="dev-builder" class="ov-input" formControlName="builder" maxlength="160" />
            </div>
          </div>
        </section>

        <section class="ov-card block" aria-labelledby="amenities-title">
          <h3 id="amenities-title">Amenities y servicios</h3>
          <div class="grid">
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="dev-amenities">Amenities</label>
              <input id="dev-amenities" class="ov-input" formControlName="amenities"
                     placeholder="Separados por coma: seguridad 24 h, SUM, pileta, plaza" />
            </div>
            <div class="ov-field wide">
              <label class="ov-eyebrow" for="dev-services">Servicios</label>
              <input id="dev-services" class="ov-input" formControlName="services"
                     placeholder="Separados por coma: agua corriente, cloacas, gas natural, fibra óptica" />
            </div>
          </div>
        </section>

        <div class="save-bar">
          @if (error(); as message) {
            <p class="ov-error" role="alert">{{ message }}</p>
          }
          @if (saved()) {
            <p class="saved" role="status">Datos guardados.</p>
          }
          <button class="ov-btn ov-btn--primary" type="submit" [disabled]="saving() || form.invalid">
            {{ saving() ? 'Guardando…' : 'Guardar datos' }}
          </button>
        </div>
      </form>

      <section class="ov-card block" aria-labelledby="cover-title">
        <h3 id="cover-title">Portada del desarrollo</h3>
        <div class="cover-row">
          <div class="cover">
            @if (store.development()?.coverUrl) {
              <img [src]="store.development() | image: 'cover' : 'screen'" alt="Portada actual del desarrollo" />
            } @else {
              <span class="muted">Sin portada</span>
            }
          </div>
          <div class="cover-text">
            <p class="muted">Un render o una foto aérea del barrio, en JPG, PNG o WebP. Encabeza la vista del comprador.</p>
            <label class="ov-btn ov-btn--secondary file">
              {{ uploadingCover() ? 'Subiendo…' : (store.development()?.coverUrl ? 'Reemplazar portada' : 'Subir portada') }}
              <input type="file" accept="image/jpeg,image/png,image/webp" (change)="uploadCover($event)" [disabled]="uploadingCover()" />
            </label>
            @if (coverError(); as message) {
              <p class="ov-error" role="alert">{{ message }}</p>
            }
          </div>
        </div>
      </section>

      @if (store.development(); as work) {
        <section class="ov-card block" aria-labelledby="documents-title">
          <h3 id="documents-title">Documentos para todos los compradores</h3>
          <p class="muted">Reglamento, memoria descriptiva, planos generales. Los documentos de una casa se cargan en su ficha.</p>
          <app-documents-manager [developmentId]="work.id" [unitId]="null" />
        </section>
      }
    </div>
  `,
  styles: `
    .data-page {
      display: grid;
      gap: var(--ov-space-5);
      --ov-measure: 60rem;
      padding-top: var(--ov-space-6);
    }

    header .ov-lead {
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

    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
      gap: var(--ov-space-4);
    }

    .wide {
      grid-column: 1 / -1;
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .save-bar {
      position: sticky;
      bottom: 0;
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

    .cover-row {
      display: grid;
      gap: var(--ov-space-5);
      align-items: center;
    }

    .cover {
      display: grid;
      place-items: center;
      overflow: hidden;
      aspect-ratio: 16 / 9;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .cover img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .cover-text {
      display: grid;
      gap: var(--ov-space-3);
      justify-items: start;
    }

    .file {
      position: relative;
      overflow: hidden;
    }

    .file input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .file:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    @media (min-width: 48rem) {
      .cover-row {
        grid-template-columns: 20rem 1fr;
      }
    }
  `,
})
export class DevelopmentDataComponent implements OnInit, LeaveGuarded {

  protected readonly store = inject(WorkStore);
  private readonly developments = inject(DevelopmentsService);
  private readonly context = inject(UserContextService);
  private readonly field = inject(FieldHouseStore);
  private readonly formBuilder = inject(FormBuilder);

  protected readonly types = DEVELOPMENT_TYPE_OPTIONS;
  protected readonly typeLabels = DEVELOPMENT_TYPE_LABELS;
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly uploadingCover = signal(false);
  protected readonly coverError = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    developmentType: [''],
    address: [''],
    locality: [''],
    province: [''],
    description: [''],
    landAreaM2: [null as number | null, Validators.min(1)],
    startedOn: [''],
    estimatedDelivery: [''],
    permitNumber: [''],
    architect: [''],
    builder: [''],
    amenities: [''],
    services: [''],
  });

  constructor() {
    warnOnUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.form.dirty ? UNSAVED_CHANGES : null;
  }

  ngOnInit(): void {
    const work = this.store.development();
    if (work) {
      this.fill(work);
    }
  }

  protected save(): void {
    const work = this.store.development();
    const value = this.form.getRawValue();
    if (!work || this.form.invalid || !value.name.trim()) {
      return;
    }
    const amenities = parseFeatures(value.amenities);
    const services = parseFeatures(value.services);
    if (amenities.length > 20 || services.length > 20) {
      this.error.set('Se cargan hasta 20 amenities y 20 servicios.');
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    this.developments.update(work.id, {
      name: value.name.trim(),
      address: optionalText(value.address),
      description: optionalText(value.description),
      locality: optionalText(value.locality),
      province: optionalText(value.province),
      developmentType: (value.developmentType || null) as DevelopmentType | null,
      landAreaM2: optionalNumber(value.landAreaM2),
      startedOn: value.startedOn || null,
      estimatedDelivery: value.estimatedDelivery || null,
      amenities,
      services,
      architect: optionalText(value.architect),
      builder: optionalText(value.builder),
      permitNumber: optionalText(value.permitNumber),
    }).subscribe({
      next: updated => {
        this.saving.set(false);
        this.saved.set(true);
        this.fill(updated);
        this.form.markAsPristine();
        this.store.reload();
        this.refreshElsewhere();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(errorMessageOf(error, 'No se pudieron guardar los datos del desarrollo.'));
      },
    });
  }

  /** El nombre y la portada también están en el menú lateral y en Hoy. */
  private refreshElsewhere(): void {
    this.context.refresh().subscribe({ error: () => undefined });
    this.field.invalidate();
  }

  protected uploadCover(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    const work = this.store.development();
    if (!file || !work) {
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      this.coverError.set('La portada tiene que ser una imagen JPG, PNG o WebP.');
      return;
    }
    this.uploadingCover.set(true);
    this.coverError.set(null);
    this.developments.uploadCover(work.id, file).subscribe({
      next: () => {
        this.uploadingCover.set(false);
        this.store.reload();
        this.refreshElsewhere();
      },
      error: (error: unknown) => {
        this.uploadingCover.set(false);
        this.coverError.set(errorMessageOf(error, 'No se pudo subir. Probá de nuevo en un rato.'));
      },
    });
  }

  private fill(work: Development): void {
    this.form.setValue({
      name: work.name,
      developmentType: work.developmentType ?? '',
      address: work.address ?? '',
      locality: work.locality ?? '',
      province: work.province ?? '',
      description: work.description ?? '',
      landAreaM2: work.landAreaM2,
      startedOn: work.startedOn ?? '',
      estimatedDelivery: work.estimatedDelivery ?? '',
      permitNumber: work.permitNumber ?? '',
      architect: work.architect ?? '',
      builder: work.builder ?? '',
      amenities: work.amenities.join(', '),
      services: work.services.join(', '),
    });
  }
}
