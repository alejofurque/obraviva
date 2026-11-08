import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HistoryFilter, StageFilterOption } from './history-derivations';

/**
 * El costado de la Historia: publicar (con el cambio de la etapa en el mismo paso) o
 * contarlo con un audio; filtrar por etapa con cuántos avances tiene cada una; y el aviso de
 * las fotos sin lugar, que lleva a los avances donde asignárselo. Sin fotos sin lugar, el
 * aviso no está.
 */
@Component({
  selector: 'app-history-aside',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="publish" aria-labelledby="publish-title">
      <h3 id="publish-title">Publicar un avance</h3>
      <p>Qué se hizo, en qué etapa, con fotos. Podés mover el porcentaje de la etapa en el mismo paso.</p>
      <button type="button" class="ov-btn primary" (click)="publish.emit()">Publicar avance</button>
      <a class="ov-btn secondary" [routerLink]="audioLink()">Contarlo con un audio</a>
      @if (comparisonsLink(); as link) {
        <a class="link" [routerLink]="link">Proyecto y realidad</a>
      }
    </section>

    @if (filters().length > 2) {
      <section class="ov-card filters" aria-labelledby="filter-title">
        <h3 id="filter-title">Filtrar por etapa</h3>
        <div role="radiogroup" aria-labelledby="filter-title" class="options">
          @for (option of filters(); track option.key) {
            <button type="button" role="radio" class="option" [class.active]="option.key === filter()"
                    [attr.aria-checked]="option.key === filter()" [attr.aria-label]="option.name + ', ' + option.count + (option.count === 1 ? ' avance' : ' avances')"
                    (click)="filterChange.emit(option.key)">
              <span>{{ option.name }}</span><span class="count ov-num">{{ option.count }}</span>
            </button>
          }
        </div>
      </section>
    }

    @if (unplaced() > 0) {
      <section class="ov-card unplaced" aria-labelledby="unplaced-title">
        <span class="dot" aria-hidden="true"></span>
        <div>
          <h3 id="unplaced-title">{{ unplaced() === 1 ? '1 foto sin lugar' : unplaced() + ' fotos sin lugar' }}</h3>
          <p>El comprador las ve en General. Asignales su lugar para que aparezcan donde corresponde.</p>
          <button type="button" class="ov-link" [attr.aria-pressed]="onlyUnplaced()" (click)="unplacedToggle.emit()">
            {{ onlyUnplaced() ? 'Ver toda la historia' : 'Asignar lugares' }}
          </button>
        </div>
      </section>
    }
  `,
  styles: `
    :host {
      display: grid;
      align-content: start;
      gap: var(--ov-space-4);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .publish {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .publish h3 {
      color: inherit;
    }

    .publish p {
      color: color-mix(in srgb, var(--ov-on-ink) 80%, transparent);
      font-size: var(--ov-text-sm);
      line-height: 1.45;
    }

    .primary {
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .secondary {
      border: 1px solid color-mix(in srgb, var(--ov-on-ink) 35%, transparent);
      background: transparent;
      color: var(--ov-on-ink);
    }

    .link {
      color: var(--ov-on-ink);
      font-size: var(--ov-text-sm);
      text-align: center;
    }

    .filters,
    .unplaced {
      padding: var(--ov-space-4) var(--ov-space-5);
    }

    .filters {
      display: grid;
      gap: var(--ov-space-2);
    }

    .options {
      display: grid;
      gap: var(--ov-space-1);
    }

    .option {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-2);
      min-height: 2.75rem;
      padding: 0 var(--ov-space-3);
      border: 0;
      border-radius: var(--ov-radius-sm);
      background: transparent;
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-sm);
      text-align: left;
      cursor: pointer;
    }

    .option.active {
      background: var(--ov-surface-muted);
      font-weight: var(--ov-weight-semibold);
    }

    .count {
      color: var(--ov-ink-muted);
    }

    .unplaced {
      display: flex;
      gap: var(--ov-space-3);
      border-color: color-mix(in srgb, var(--ov-status-delayed) 40%, var(--ov-line));
      font-size: var(--ov-text-sm);
      line-height: 1.45;
    }

    .unplaced p {
      color: var(--ov-ink-muted);
    }

    .dot {
      flex: 0 0 0.5rem;
      height: 0.5rem;
      margin-top: 0.4rem;
      border-radius: 50%;
      background: var(--ov-status-delayed);
    }

    /* En el teléfono y la tablet el filtro es una fila de chips: no empuja la historia hacia abajo. */
    @media (max-width: 64rem) {
      .options {
        display: flex;
        gap: var(--ov-space-2);
        margin: 0 calc(var(--ov-space-5) * -1);
        padding: 0 var(--ov-space-5) var(--ov-space-1);
        overflow-x: auto;
      }

      .option {
        flex: none;
        border: 1px solid var(--ov-line);
        border-radius: var(--ov-radius-pill);
        white-space: nowrap;
      }
    }
  `,
})
export class HistoryAsideComponent {
  readonly filters = input<StageFilterOption[]>([]);
  readonly filter = input<HistoryFilter>('all');
  readonly unplaced = input(0);
  readonly onlyUnplaced = input(false);
  readonly audioLink = input.required<string[]>();
  /** Sólo para el dueño. */
  readonly comparisonsLink = input<string[] | null>(null);

  readonly publish = output<void>();
  readonly filterChange = output<HistoryFilter>();
  readonly unplacedToggle = output<void>();
}
