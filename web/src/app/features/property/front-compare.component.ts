import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { PublicFront } from '../../core/api/api.models';
import { CompareSliderComponent } from '../../shared/ui/compare-slider.component';
import { FrontOption, frontOptions } from './places-derivations';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * "Tu casa, de frente": la foto de hoy del frente contra el proyecto o contra cuando
 * empezó. El selector desaparece si hay una sola opción; sin ninguna, la sección no está.
 */
@Component({
  selector: 'app-front-compare',
  imports: [ShortDatePipe, DatePipe, CompareSliderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (active(); as option) {
      <section class="front" aria-labelledby="front-title">
        <h2 id="front-title" class="ov-display heading">{{ heading() }}</h2>
        @if (lead()) {
          <p class="lead">{{ lead() }}</p>
        }
        @if (options().length > 1) {
          <div class="choices" role="radiogroup" aria-label="Comparar con">
            @for (item of options(); track item.key) {
              <button type="button" role="radio" class="chip ov-hit" [class.active]="item.key === option.key"
                      [attr.aria-checked]="item.key === option.key" (click)="chosen.set(item.key)">{{ item.label }}</button>
            }
          </div>
        }
        <app-compare-slider
          [beforeUrl]="option.beforeUrl"
          [afterUrl]="option.afterUrl"
          [beforeLabel]="option.beforeDate ? option.label + ' · ' + (option.beforeDate | shortDate: 'MMM y') : option.label"
          [afterLabel]="(option.afterDate | shortDate: 'd MMM y') ?? ''" />
        <p class="caption">
          Deslizá para comparar la foto del {{ option.afterDate | date: "d 'de' MMMM" }}
          con {{ option.key === 'project' ? 'cómo se proyectó' : 'la primera foto' }}.
        </p>
      </section>
    }
  `,
  styles: `
    .front {
      display: grid;
      gap: var(--ov-space-4);
    }

    h2 {
      font-size: var(--ov-text-2xl);
    }

    .lead {
      margin-top: calc(var(--ov-space-3) * -1);
      color: var(--ov-ink-muted);
    }

    /* En escritorio, a 4:3 y a todo el ancho, el frente ocuparía casi 900 px. */
    app-compare-slider {
      --compare-max-height: 35rem;
    }

    @media (max-width: 40rem) {
      h2 {
        font-size: var(--ov-text-xl);
      }
    }

    .choices {
      display: flex;
      gap: var(--ov-space-2);
    }

    .chip {
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .chip.active {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .caption {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class FrontCompareComponent {

  readonly front = input.required<PublicFront | null>();
  /** En "Mi casa", "Del proyecto a la realidad". */
  readonly heading = input('Tu casa, de frente');
  readonly lead = input<string | null>(null);

  protected readonly chosen = signal<'project' | 'start' | null>(null);
  protected readonly options = computed(() => frontOptions(this.front()));
  /** Por omisión, el proyecto; si la vista se renueva sin la opción elegida, la primera que haya. */
  protected readonly active = computed<FrontOption | null>(() =>
    this.options().find(option => option.key === this.chosen()) ?? this.options()[0] ?? null);
}
