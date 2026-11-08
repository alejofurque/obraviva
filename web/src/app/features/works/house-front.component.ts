import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ImagePipe, VariantPipe, imageVariantOf } from '../../shared/media/variant.pipe';
import { PublicFront } from '../../core/api/api.models';
import { MyPropertyService } from '../../core/api/my-property.service';
import { UnitsService } from '../../core/api/units.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { CompareSliderComponent } from '../../shared/ui/compare-slider.component';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * "Imágenes principales" de la ficha: la portada (la imagen de la casa en Mi propiedad, en la
 * grilla del desarrollo y en la portada del comprador si no hay fotos del frente), el render del
 * frente (para "Del proyecto a la realidad" y "Lo que viene") y la foto de hoy del frente, que
 * sube el encargado.
 *
 * <p>Es una ficha de carga, no una vitrina: tres filas con su miniatura, para qué sirve y su
 * acción, y la vista del comprador plegada. La foto de hoy sale de la misma vista que recibe el
 * comprador (`/api/my-units/:id`), no de repetir acá la regla: así el dueño ve exactamente la foto
 * que va a ver el comprador.
 */
@Component({
  selector: 'app-house-front',
  imports: [ShortDatePipe, ImagePipe, DatePipe, RouterLink, CompareSliderComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card block" aria-labelledby="main-images-title">
      <h3 id="main-images-title">Imágenes principales</h3>

      <div class="row cover">
        @if (coverUrl(); as url) {
          <img class="thumb" [src]="url" alt="Portada actual de la casa" />
        } @else {
          <span class="thumb empty" aria-hidden="true"></span>
        }
        <div class="text">
          <p class="title">Portada</p>
          <p class="muted">Se usa si no hay fotos del frente.</p>
          <label class="ov-link file">
            {{ uploadingCover() ? 'Subiendo…' : (coverUrl() ? 'Cambiar' : 'Subir portada') }}
            <input type="file" accept="image/*" [disabled]="uploadingCover()" (change)="uploadCover($event)" />
          </label>
        </div>
      </div>

      <div class="row slot">
        @if (front()?.renderUrl) {
          <img class="thumb" [src]="front() | image: 'render' : 'thumb'" alt="Render del frente de la casa" />
        } @else {
          <span class="thumb empty" aria-hidden="true"></span>
        }
        <div class="text">
          <p class="title">Render del frente</p>
          <p class="muted">Para "Del proyecto a la realidad" y "Lo que viene".</p>
          @if (!front()?.renderUrl) {
            <p class="status missing">Sin render todavía</p>
          }
          <label class="ov-link file">
            {{ uploading() ? 'Subiendo…' : (front()?.renderUrl ? 'Cambiar' : 'Subir render') }}
            <input type="file" accept="image/*" [disabled]="uploading() || loading()" (change)="uploadRender($event)" />
          </label>
        </div>
      </div>

      <div class="row slot">
        @if (front()?.latest; as photo) {
          <img class="thumb" [src]="photo | variant: 'thumb'" [alt]="'Foto de hoy de ' + front()!.sector" />
        } @else {
          <span class="thumb empty" aria-hidden="true"></span>
        }
        <div class="text">
          <p class="title">Foto de hoy del frente</p>
          @if (front()?.latest; as photo) {
            <p class="muted">La sube el encargado.</p>
            <p class="status">{{ front()!.sector }} · {{ photo.date | date: "d 'de' MMMM 'de' y" }}</p>
          } @else if (loading()) {
            <p class="status">Cargando…</p>
          } @else if (!front()?.sector) {
            <p class="status missing">Esta casa no tiene un lugar marcado como frente.</p>
            <a class="ov-link" [routerLink]="sectorsLink()" [queryParams]="{ sectores: 'editar' }">Cambiar sectores</a>
          } @else {
            <p class="status missing">
              Todavía no hay fotos del frente. Las sube el encargado desde Recorrido de hoy, en el lugar {{ front()!.sector }}.
            </p>
          }
        </div>
      </div>

      @if (front()?.renderUrl && front()?.latest) {
        <!-- Plegada: en la pantalla del dueño la comparación se revisa, no se exhibe. -->
        <details class="ov-disclosure preview">
          <summary class="ov-link">Ver cómo lo ve el comprador</summary>
          <app-compare-slider [beforeUrl]="(front() | image: 'render' : 'screen') ?? ''" [afterUrl]="(front()!.latest! | variant: 'screen') ?? ''" beforeLabel="El proyecto"
                              [afterLabel]="(front()!.latest!.date | shortDate: 'd MMM y') ?? ''" />
        </details>
      }

      @if (error(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }
    </section>
  `,
  styles: `
    /* El mismo marco que los demás bloques de la ficha. */
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

    /* Una fila por imagen: la miniatura y, al lado, para qué sirve y su acción. */
    .row {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    .row + .row {
      padding-top: var(--ov-space-4);
      border-top: 1px solid var(--ov-line);
    }

    .text {
      display: grid;
      gap: var(--ov-space-1);
      justify-items: start;
      font-size: var(--ov-text-sm);
    }

    .title {
      font-weight: var(--ov-weight-semibold);
    }

    .thumb {
      flex: 0 0 7.5rem;
      width: 7.5rem;
      height: 5.625rem;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
      object-fit: cover;
    }

    .thumb.empty {
      border: 1px dashed var(--ov-line-strong);
      background: var(--ov-canvas);
    }

    .status {
      font-size: var(--ov-text-sm);
    }

    .status.missing {
      color: var(--ov-ink-muted);
    }

    .file {
      position: relative;
      min-height: 2.75rem;
      display: inline-flex;
      align-items: center;
      cursor: pointer;
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

    .preview summary {
      display: inline-flex;
      align-items: center;
      min-height: 2.75rem;
      cursor: pointer;
    }

    .preview app-compare-slider {
      display: block;
      max-width: 32rem;
      margin-top: var(--ov-space-3);
    }
  `,
})
export class HouseFrontComponent implements OnInit {

  private readonly house = inject(HouseStore);
  private readonly work = inject(WorkStore);
  private readonly myProperty = inject(MyPropertyService);
  private readonly units = inject(UnitsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly front = signal<PublicFront | null>(null);
  protected readonly uploadingCover = signal(false);
  /** La URL firmada llega con el resumen de la casa; mientras, se muestra el archivo local. */
  private readonly coverOverride = signal<string | null>(null);
  protected readonly coverUrl = computed(() => this.coverOverride() ?? imageVariantOf(this.house.unit(), 'cover', 'thumb'));
  protected readonly loading = signal(true);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected sectorsLink(): string[] {
    return ['/obras', this.work.development()?.id ?? '', 'casas', this.house.unitId(), 'recorridos'];
  }

  protected uploadCover(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.error.set('La portada tiene que ser una imagen.');
      return;
    }
    this.uploadingCover.set(true);
    this.error.set(null);
    this.units.uploadCover(this.house.unitId(), file).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.uploadingCover.set(false);
        this.coverOverride.set(URL.createObjectURL(file));
        this.house.reload();
      },
      error: (error: unknown) => {
        this.uploadingCover.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo subir. Probá de nuevo en un rato.'));
      },
    });
  }

  protected uploadRender(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.error.set('El render tiene que ser una imagen.');
      return;
    }
    this.uploading.set(true);
    this.error.set(null);
    this.units.uploadFrontRender(this.house.unitId(), file).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.uploading.set(false);
        this.load();
      },
      error: (error: unknown) => {
        this.uploading.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo subir. Probá de nuevo en un rato.'));
      },
    });
  }

  private load(): void {
    this.myProperty.property(this.house.unitId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: response => {
        this.front.set(response.property.front);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo cargar el frente de la casa.'));
      },
    });
  }
}
