import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Encabezado de página del área del dueño, el mismo en el desarrollo y en la casa: la foto,
 * una línea de contexto, el título con sus estados al lado y, a la derecha, las acciones.
 *
 * <p>El contexto, los estados y las acciones se proyectan: `[header-context]`,
 * `[header-states]` y `[header-actions]`. Sin foto, el título arranca en el borde.
 *
 * <p>`compact`: en el teléfono, sin foto, el contexto en una línea y las acciones en la misma
 * fila que el título, para que el encabezado no se coma la pantalla antes del contenido.
 */
@Component({
  selector: 'app-page-header',
  host: { '[class.compact]': 'compact()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="identity">
      @if (imageUrl()) {
        <img class="photo" [class.wide]="wideImage()" [src]="imageUrl()" alt="" />
      }
      <div class="text">
        <p class="context"><ng-content select="[header-context]" /></p>
        <div class="title-row">
          <h1>{{ heading() }}</h1>
          <ng-content select="[header-states]" />
        </div>
      </div>
    </div>
    <div class="actions">
      <ng-content select="[header-actions]" />
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-4) var(--ov-space-5);
    }

    .identity {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
      min-width: 0;
    }

    .photo {
      flex: none;
      width: 4.5rem;
      height: 4.5rem;
      border-radius: var(--ov-radius-md);
      object-fit: cover;
      background: var(--ov-surface-muted);
    }

    .photo.wide {
      width: 5.25rem;
      height: 4rem;
    }

    .text {
      display: grid;
      gap: 0.125rem;
      min-width: 0;
    }

    .context {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .title-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-2) var(--ov-space-3);
    }

    h1 {
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
      overflow-wrap: anywhere;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3) var(--ov-space-4);
    }

    .actions:empty {
      display: none;
    }

    @media (max-width: 40rem) {
      :host(.compact) {
        flex-wrap: nowrap;
        gap: var(--ov-space-3);
      }

      :host(.compact) .identity {
        flex: 1 1 auto;
      }

      :host(.compact) .photo {
        display: none;
      }

      :host(.compact) .context {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      :host(.compact) .actions {
        flex: none;
        flex-wrap: nowrap;
        gap: var(--ov-space-2);
      }

      .photo {
        width: 3.5rem;
        height: 3.5rem;
      }

      .photo.wide {
        width: 4rem;
        height: 3rem;
      }
    }
  `,
})
export class PageHeaderComponent {
  readonly heading = input.required<string>();
  readonly imageUrl = input<string | null>(null);
  /** La foto de una casa es apaisada; la de un desarrollo, cuadrada. */
  readonly wideImage = input(false);
  /** En el teléfono, una versión baja: sin foto y con las acciones al lado del título. */
  readonly compact = input(false);
}
