import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FrontPiece } from './places-derivations';

interface PieceLink {
  label: string;
  link: string[];
  query: Record<string, string> | null;
}

/**
 * Aviso de "Ver como el comprador": qué falta cargar para que una sección aparezca, con un
 * enlace a donde se carga cada cosa. Sólo se muestra si el backend marcó la vista como del
 * dueño (`preview`); el comprador nunca lo ve, ve la sección o nada.
 */
@Component({
  selector: 'app-preview-notice',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="notice" [attr.aria-label]="'Falta cargar: ' + title()">
      <p>
        <strong>{{ title() }}:</strong> {{ verb() }}
        @for (item of links(); track item.label; let last = $last; let index = $index) {
          @if (index > 0) { {{ last ? ' y ' : ', ' }} }
          <a [routerLink]="item.link" [queryParams]="item.query">{{ item.label }}</a>
        }.
      </p>
      <p class="hint">Sólo lo ves vos: el comprador no ve esta sección hasta que esté completa.</p>
    </aside>
  `,
  styles: `
    .notice {
      display: grid;
      gap: var(--ov-space-1);
      padding: var(--ov-space-4) var(--ov-space-5);
      border: 1px dashed var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      font-size: var(--ov-text-sm);
    }

    a {
      color: var(--ov-accent);
      font-weight: var(--ov-weight-medium);
      text-decoration: underline;
    }

    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }
  `,
})
export class PreviewNoticeComponent {

  readonly title = input.required<string>();
  readonly pieces = input.required<FrontPiece[]>();
  readonly developmentId = input.required<string>();
  readonly unitId = input.required<string>();

  /** "faltan fotos del frente", "falta marcar el frente y el render del proyecto". */
  protected readonly verb = computed(() => {
    const pieces = this.pieces();
    return pieces[0] !== 'front' && (pieces.length > 1 || pieces[0] === 'photos') ? 'faltan' : 'falta';
  });

  /** Cada cosa que falta lleva a la pantalla donde se carga. */
  protected readonly links = computed<PieceLink[]>(() => {
    const house = ['/obras', this.developmentId(), 'casas', this.unitId()];
    return this.pieces().map(piece => {
      switch (piece) {
        case 'front':
          return { label: 'marcar el frente', link: [...house, 'recorridos'], query: { sectores: 'editar' } };
        case 'photos':
          return { label: 'fotos del frente', link: ['/hoy', this.unitId(), 'recorrido'], query: null };
        default:
          return { label: 'el render del proyecto', link: [...house, 'ficha'], query: null };
      }
    });
  });
}
