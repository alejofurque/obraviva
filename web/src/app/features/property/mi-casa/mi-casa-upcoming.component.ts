import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Upcoming } from '../buyer-derivations';

/**
 * "Lo que viene": la próxima etapa, lo que incluye y cuándo está prevista, en un bloque oscuro
 * con el render del frente ("Así va a quedar"). Sin render, el bloque va sin imagen.
 */
@Component({
  selector: 'app-mi-casa-upcoming',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let next = upcoming();
    <section class="upcoming" aria-labelledby="upcoming-title">
      <div class="text">
        <p class="eyebrow">Lo que viene@if (next.when) { · previsto para {{ next.when }}}</p>
        <h2 id="upcoming-title" class="ov-display heading">{{ next.stage.name }}</h2>
        @if (next.stage.description) {
          <p class="description">{{ next.stage.description }}</p>
        }
        @if (next.after || next.last) {
          <p class="then">
            @if (next.after) { <span>Después: {{ lower(next.after) }}</span> }
            @if (next.last) { <span>Y al final: {{ lower(next.last) }}</span> }
          </p>
        }
      </div>
      @if (renderUrl(); as render) {
        <div class="render">
          <img [src]="render" alt="Render del proyecto de la casa" loading="lazy" />
          <span class="tag">Así va a quedar</span>
        </div>
      }
    </section>
  `,
  styles: `
    .upcoming {
      display: flex;
      flex-wrap: wrap;
      overflow: hidden;
      border-radius: var(--ov-radius-lg);
      background: color-mix(in srgb, var(--ov-accent) 40%, var(--ov-ink));
      color: var(--ov-on-media);
    }

    .text {
      display: grid;
      flex: 1 1 24rem;
      align-content: center;
      gap: var(--ov-space-3);
      padding: var(--ov-space-8);
    }

    .eyebrow {
      color: var(--ov-on-media-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .heading {
      color: inherit;
      font-size: var(--ov-text-2xl);
    }

    .description {
      max-width: 34rem;
      color: var(--ov-on-media-muted);
      line-height: 1.6;
    }

    .then {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2) var(--ov-space-6);
      color: var(--ov-on-media-muted);
      font-size: var(--ov-text-sm);
    }

    .render {
      position: relative;
      flex: 1 1 22rem;
      min-height: 18rem;
    }

    .render img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .tag {
      position: absolute;
      top: var(--ov-space-4);
      left: var(--ov-space-4);
      padding: 0.3rem var(--ov-space-3);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    @media (max-width: 40rem) {
      .text {
        padding: var(--ov-space-6);
      }

      .heading {
        font-size: var(--ov-text-xl);
      }
    }
  `,
})
export class MiCasaUpcomingComponent {
  readonly upcoming = input.required<Upcoming>();
  readonly renderUrl = input<string | null>(null);

  protected lower(name: string): string {
    return name.charAt(0).toLowerCase() + name.slice(1);
  }
}
