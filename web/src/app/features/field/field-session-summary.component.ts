import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** El resumen del recorrido del día: un anillo con "2/5" y "2 de 5 lugares con material". */
@Component({
  selector: 'app-field-session-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="summary" role="status">
      <span class="ring" aria-hidden="true" [style.--done.%]="percent()">
        <span class="ov-num">{{ done() }}/{{ total() }}</span>
      </span>
      <p>
        <strong>{{ done() }} de {{ total() }} lugares con material</strong>
        <span class="hint">Pasá por cada lugar: 3D, fotos o las dos.</span>
      </p>
    </div>
  `,
  styles: `
    .summary {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      font-size: var(--ov-text-sm);
    }

    p {
      display: grid;
    }

    .hint {
      color: var(--ov-ink-muted);
    }

    .ring {
      display: grid;
      flex: 0 0 3.25rem;
      place-items: center;
      height: 3.25rem;
      border-radius: 50%;
      background: conic-gradient(var(--ov-accent) 0 var(--done), var(--ov-line) var(--done) 100%);
    }

    .ring span {
      display: grid;
      place-items: center;
      width: 2.5rem;
      height: 2.5rem;
      border-radius: 50%;
      background: var(--ov-surface);
      font-weight: var(--ov-weight-semibold);
    }
  `,
})
export class FieldSessionSummaryComponent {
  readonly done = input.required<number>();
  readonly total = input.required<number>();
  protected readonly percent = computed(() => this.total() === 0 ? 0 : (this.done() / this.total()) * 100);
}
