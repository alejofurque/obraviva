import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EmptyStateComponent } from '../../shared/ui/page-states.component';
import { ImagePipe, VariantPipe, variantOf } from '../../shared/media/variant.pipe';
import { PhotoViewerComponent, ViewerPhoto } from './photo-viewer.component';
import { houseFacts, monthYear } from './property-derivations';
import { PropertyViewStore } from './property-view.store';

const DEVELOPMENT_TYPES: Record<string, string> = {
  BARRIO_CERRADO: 'Barrio cerrado',
  BARRIO_ABIERTO: 'Barrio abierto',
  COMPLEJO: 'Complejo de casas',
  LOTEO: 'Loteo',
};

/**
 * La casa y el barrio, para quien la compró o la está mirando: la galería, cómo es la
 * casa, qué incluye y dónde está. Sólo muestra lo que el desarrollista cargó; lo vacío no
 * aparece.
 */
@Component({
  selector: 'app-property-home',
  imports: [ImagePipe, DecimalPipe, EmptyStateComponent, PhotoViewerComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.view(); as view) {
      <div class="ov-page home">
        @if (view.showcase.length > 0) {
          <section class="model" aria-labelledby="model-title">
          <div class="model-head">
            <p id="model-title" class="ov-eyebrow">Imágenes del modelo</p>
            <p class="model-note">Renders y fotos del modelo terminado: así va a quedar tu casa.</p>
          </div>
          <div class="gallery">
            @for (photo of view.showcase; track photo.url; let i = $index) {
              <button type="button" class="shot" [class.main]="i === 0" (click)="viewer.open(i)"
                      [attr.aria-label]="'Ampliar imagen ' + (i + 1) + ' de ' + view.showcase.length">
                <img [src]="photo | variant: (i === 0 ? 'screen' : 'thumb')" alt="" [attr.loading]="i === 0 ? null : 'lazy'" />
              </button>
            }
          </div>
          </section>
        }

        <section class="about" aria-labelledby="about-title">
          <div class="about-text">
            <h2 id="about-title">{{ view.details.modelName ?? view.unitCode }}</h2>
            @if (view.unitDescription) {
              <p class="tagline">{{ view.unitDescription }}</p>
            }
            @if (view.details.longDescription) {
              <p class="long">{{ view.details.longDescription }}</p>
            }
            @if (!view.details.longDescription && facts().length === 0) {
              <app-empty-state title="La ficha de la casa todavía no está cargada"
                message="Cuando la desarrolladora la complete, vas a ver acá cómo es tu casa." />
            }
          </div>

          @if (facts().length > 0) {
            <dl class="facts">
              @for (fact of facts(); track fact.label) {
                <div><dt>{{ fact.label }}</dt><dd class="ov-num">{{ fact.value }}</dd></div>
              }
              @if (delivery(); as month) {
                <div><dt>Entrega estimada</dt><dd class="month">{{ month }}</dd></div>
              }
            </dl>
          }
        </section>

        @if (view.details.features.length > 0 || view.details.finishes) {
          <section class="includes" aria-labelledby="includes-title">
            <h2 id="includes-title">Qué incluye</h2>
            @if (view.details.features.length > 0) {
              <ul class="features">
                @for (feature of view.details.features; track feature) {
                  <li>{{ feature }}</li>
                }
              </ul>
            }
            @if (view.details.finishes) {
              <div class="finishes">
                <h3>Terminaciones</h3>
                <p>{{ view.details.finishes }}</p>
              </div>
            }
          </section>
        }

        <section class="barrio" aria-labelledby="barrio-title">
          @if (view.development.coverUrl) {
            <img class="barrio-cover" [src]="view.development | image: 'cover' : 'screen'" [alt]="'Imagen de ' + view.developmentName" loading="lazy" />
          }
          <div class="barrio-body">
            <p class="barrio-type">{{ typeLabel() }}</p>
            <h2 id="barrio-title">{{ view.developmentName }}</h2>
            @if (place()) {
              <p class="place">{{ place() }}</p>
            }
            @if (view.development.description) {
              <p class="long">{{ view.development.description }}</p>
            }

            @if (view.development.amenities.length > 0) {
              <div class="group">
                <h3>En el barrio</h3>
                <ul class="features">
                  @for (amenity of view.development.amenities; track amenity) {
                    <li>{{ amenity }}</li>
                  }
                </ul>
              </div>
            }
            @if (view.development.services.length > 0) {
              <div class="group">
                <h3>Servicios</h3>
                <ul class="features">
                  @for (service of view.development.services; track service) {
                    <li>{{ service }}</li>
                  }
                </ul>
              </div>
            }

            <dl class="credits">
              @if (view.development.landAreaM2) {
                <div><dt>Superficie del barrio</dt><dd class="ov-num">{{ view.development.landAreaM2 | number: '1.0-0' }} m²</dd></div>
              }
              @if (view.development.architect) {
                <div><dt>Proyecto</dt><dd>{{ view.development.architect }}</dd></div>
              }
              @if (view.development.builder) {
                <div><dt>Construye</dt><dd>{{ view.development.builder }}</dd></div>
              }
            </dl>
          </div>
        </section>
      </div>

      <app-photo-viewer #viewer [photos]="viewerPhotos()" alt="Imagen ampliada de la casa" />
    }
  `,
  styles: `
    .home {
      display: grid;
      gap: var(--ov-space-8);
      padding-top: var(--ov-space-6);
    }

    h2 {
      font-size: var(--ov-text-xl);
      letter-spacing: -0.015em;
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .model {
      display: grid;
      gap: var(--ov-space-3);
    }

    .model-note {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .gallery {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--ov-space-3);
    }

    .shot {
      padding: 0;
      overflow: hidden;
      border: 0;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      aspect-ratio: 4 / 3;
      cursor: zoom-in;
    }

    .shot.main {
      grid-column: 1 / -1;
      aspect-ratio: 16 / 9;
      border-radius: var(--ov-radius-lg);
    }

    .shot:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .shot img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      transition: transform var(--ov-duration) var(--ov-ease);
    }

    .shot:hover img {
      transform: scale(1.02);
    }

    .about {
      display: grid;
      gap: var(--ov-space-7);
    }

    .about-text {
      display: grid;
      gap: var(--ov-space-3);
      align-content: start;
    }

    .about h2 {
      font-size: clamp(2rem, 1.5rem + 2vw, 2.75rem);
      line-height: var(--ov-leading-tight);
      letter-spacing: var(--ov-tracking-display);
    }

    .tagline {
      font-size: var(--ov-text-lg);
    }

    .long {
      max-width: var(--ov-reading-max);
      color: var(--ov-ink-muted);
      line-height: var(--ov-leading-normal);
    }

    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      align-content: start;
      margin: 0;
      border-top: 2px solid var(--ov-ink);
    }

    .facts > div {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) var(--ov-space-2);
      border-bottom: 1px solid var(--ov-line);
    }

    .facts dt {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .facts dd {
      margin: 0;
      font-weight: var(--ov-weight-semibold);
      text-align: right;
    }

    .month::first-letter {
      text-transform: uppercase;
    }

    .includes {
      display: grid;
      gap: var(--ov-space-5);
    }

    .features {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .features li {
      padding: var(--ov-space-2) var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      font-size: var(--ov-text-sm);
    }

    .finishes {
      display: grid;
      gap: var(--ov-space-2);
      max-width: var(--ov-reading-max);
    }

    .finishes p {
      color: var(--ov-ink-muted);
      line-height: var(--ov-leading-normal);
    }

    .barrio {
      display: grid;
      overflow: hidden;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      border: 1px solid var(--ov-line);
    }

    .barrio-cover {
      width: 100%;
      height: 100%;
      min-height: 14rem;
      object-fit: cover;
    }

    .barrio-body {
      display: grid;
      gap: var(--ov-space-3);
      align-content: start;
      padding: var(--ov-space-6);
    }

    .barrio-type,
    .place {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .group {
      display: grid;
      gap: var(--ov-space-3);
      margin-top: var(--ov-space-3);
    }

    .credits {
      display: grid;
      gap: var(--ov-space-2);
      margin: var(--ov-space-3) 0 0;
      font-size: var(--ov-text-sm);
    }

    .credits > div {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
    }

    .credits dt {
      color: var(--ov-ink-muted);
    }

    .credits dd {
      margin: 0;
    }

    @media (min-width: 48rem) {
      .gallery {
        grid-template-columns: repeat(4, minmax(0, 1fr));
      }

      .shot.main {
        grid-column: span 2;
        grid-row: span 2;
        aspect-ratio: auto;
      }
    }

    @media (min-width: 56rem) {
      .about {
        grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
        gap: var(--ov-space-8);
      }

      .barrio {
        grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
      }
    }
  `,
})
export class PropertyHomeComponent {

  protected readonly store = inject(PropertyViewStore);

  /** El visor ampliado muestra la versión de pantalla, no el original. */
  protected readonly viewerPhotos = computed<ViewerPhoto[]>(() =>
    (this.store.view()?.showcase ?? []).map(photo => ({ url: variantOf(photo, 'screen')! })));

  protected readonly facts = computed(() => {
    const view = this.store.view();
    return view ? houseFacts(view.details) : [];
  });

  protected readonly delivery = computed(() => {
    const month = this.store.view()?.details.estimatedDelivery;
    return month ? monthYear(month) : null;
  });

  protected readonly typeLabel = computed(() => {
    const type = this.store.view()?.development.developmentType;
    return type ? DEVELOPMENT_TYPES[type] ?? 'Desarrollo' : 'Desarrollo';
  });

  protected readonly place = computed(() => {
    const view = this.store.view();
    if (!view) {
      return '';
    }
    return [view.developmentAddress, view.development.locality, view.development.province].filter(Boolean).join(', ');
  });
}
