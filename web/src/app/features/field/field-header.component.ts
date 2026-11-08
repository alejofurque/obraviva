import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { BackLinkDirective } from '../../core/routing/back-navigation';

/**
 * El encabezado de una tarea del campo (Publicar avance, Recorrido, Audio): volver a donde se
 * estaba (Hoy, La casa) o a Hoy si se entró directo, con un área de 48 px, el título, la casa debajo y, a la derecha, lo que se proyecte (por ejemplo,
 * "Otra fecha").
 */
@Component({
  selector: 'app-field-header',
  imports: [MatIconModule, BackLinkDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="back" [appBack]="['/hoy']" aria-label="Volver"><mat-icon svgIcon="chevron_left" aria-hidden="true" /></a>
    <div class="title">
      <h1>{{ title() }}</h1>
      <p class="sub">{{ subtitle() }}</p>
    </div>
    <ng-content />
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      margin-left: calc(-1 * var(--ov-space-3));
    }

    .back {
      display: grid;
      flex: 0 0 3rem;
      place-items: center;
      height: 3rem;
      color: var(--ov-ink);
    }

    .title {
      flex: 1;
      min-width: 0;
    }

    h1 {
      font-size: var(--ov-text-lg);
      letter-spacing: -0.01em;
    }

    .sub {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class FieldHeaderComponent {
  readonly title = input.required<string>();
  readonly subtitle = input.required<string>();
}
