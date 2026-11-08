import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

/** La imagen de la portada: la última foto del frente, la portada fija, o nada. */
export interface HeroImage {
  url: string;
  caption: string | null;
}

/**
 * Portada de "Mi casa": la foto de hoy a todo lo ancho, el saludo, lo que es cierto hoy en
 * serif ("Tu casa avanza.") y la frase del último avance, tal cual se publicó. Ofrece el
 * recorrido 3D, si lo hay, y bajar a lo que cambió este mes.
 */
@Component({
  selector: 'app-mi-casa-hero',
  imports: [RouterLink, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="hero" aria-label="Tu casa hoy">
      @if (image(); as picture) {
        <img class="photo" [src]="picture.url" alt="" (error)="failed.emit(picture.url)" />
        @if (picture.caption) {
          <span class="caption">{{ picture.caption }}</span>
        }
      }
      <div class="veil">
        <div class="copy">
          @if (greeting()) {
            <p class="greeting">{{ greeting() }}</p>
          }
          <h1 class="ov-display title">{{ heading() }}</h1>
          @if (sentence()) {
            <p class="sentence">{{ sentence() }}</p>
          }
          <p class="context">{{ context() }}</p>
        </div>
        <div class="actions">
          @if (tourLink(); as link) {
            <a class="ov-btn tour" [routerLink]="link" queryParamsHandling="preserve">
              <mat-icon svgIcon="view_in_ar" aria-hidden="true" />Recorrer en 3D
            </a>
          }
          @if (hasMonth()) {
            <button type="button" class="ov-btn month" (click)="month.emit()">{{ monthLabel() }}</button>
          }
        </div>
      </div>
    </section>
  `,
  styles: `
    .hero {
      position: relative;
      display: flex;
      align-items: flex-end;
      overflow: hidden;
      min-height: 35rem;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-ink);
      color: var(--ov-on-media);
    }

    .photo {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .caption {
      position: absolute;
      top: var(--ov-space-4);
      right: var(--ov-space-4);
      padding: 0.25rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      font-size: var(--ov-text-xs);
    }

    .veil {
      position: relative;
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--ov-space-6);
      width: 100%;
      padding: 7rem var(--ov-space-8) var(--ov-space-7);
      background: linear-gradient(0deg, color-mix(in srgb, var(--ov-ink) 82%, transparent),
        color-mix(in srgb, var(--ov-ink) 45%, transparent) 55%, transparent);
    }

    .copy {
      display: grid;
      gap: var(--ov-space-3);
      max-width: 40rem;
    }

    .greeting,
    .context {
      opacity: 0.88;
    }

    .context {
      font-size: var(--ov-text-sm);
    }

    .title {
      color: inherit;
      font-size: clamp(2.5rem, 1.8rem + 3vw, 3.75rem);
    }

    .sentence {
      font-size: var(--ov-text-md);
      line-height: 1.5;
      display: -webkit-box;
      overflow: hidden;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-3);
    }

    .tour,
    .month {
      min-height: 3.25rem;
      padding: 0 var(--ov-space-6);
      font-size: var(--ov-text-md);
    }

    .tour {
      gap: var(--ov-space-2);
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .tour mat-icon {
      width: 1.25rem;
      height: 1.25rem;
    }

    .month {
      border: 1px solid color-mix(in srgb, var(--ov-on-media) 60%, transparent);
      background: transparent;
      color: var(--ov-on-media);
    }

    @media (max-width: 40rem) {
      .hero {
        min-height: 35rem;
        margin: 0 calc(var(--ov-gutter) * -1);
        border-radius: 0;
      }

      .veil {
        padding: 6rem var(--ov-gutter) var(--ov-space-6);
      }

      .actions,
      .tour,
      .month {
        width: 100%;
      }

      .tour,
      .month {
        justify-content: center;
      }
    }
  `,
})
export class MiCasaHeroComponent {
  readonly heading = input.required<string>();
  readonly greeting = input<string | null>(null);
  readonly sentence = input<string | null>(null);
  readonly context = input.required<string>();
  readonly image = input<HeroImage | null>(null);
  /** Nulo si no hay recorridos: el botón no aparece. */
  readonly tourLink = input<string[] | null>(null);
  readonly hasMonth = input(false);
  /** "Qué cambió este mes", o "Qué cambió en septiembre" si lo último es de otro mes. */
  readonly monthLabel = input('Qué cambió este mes');
  readonly month = output<void>();
  readonly failed = output<string>();
}
