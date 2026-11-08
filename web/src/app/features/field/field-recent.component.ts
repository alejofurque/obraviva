import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivityItem } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { fieldActivityLinks } from '../works/activity-derivations';
import { changeChip } from '../works/overview-derivations';
import { shortWhen } from './field-derivations';

/** Una fila de "Mis últimas publicaciones": cuándo, de qué fue, lo que cambió y el texto. */
export interface RecentRow {
  id: string;
  when: string;
  tag: string;
  change: string | null;
  text: string;
  correct: { link: string[]; query: Record<string, string> | null } | null;
}

/** "Fotos · 6 lugares", "9 fotos de Frente, Living y 3 más": lo que dice una fila de cada tipo. */
export function recentRow(item: ActivityItem, developmentId: string, today: string = todayLocal()): RecentRow {
  const when = shortWhen(item.at, today);
  const base = { id: item.id, when: when.charAt(0).toUpperCase() + when.slice(1), change: null, correct: null };
  if (item.entry) {
    return {
      ...base, tag: item.entry.stageName ?? 'General de la casa', change: changeChip(item.entry), text: item.entry.description,
      correct: fieldActivityLinks(item, developmentId).correct,
    };
  }
  if (item.session) {
    const { photos, videos, sectors } = item.session;
    const count = [photos > 0 ? `${photos} ${photos === 1 ? 'foto' : 'fotos'}` : '', videos > 0 ? `${videos} ${videos === 1 ? 'video' : 'videos'}` : '']
      .filter(Boolean).join(' y ');
    const places = sectors.length <= 3 ? sectors.join(', ') : `${sectors.slice(0, 3).join(', ')} y ${sectors.length - 3} más`;
    return {
      ...base, tag: `Fotos · ${sectors.length} ${sectors.length === 1 ? 'lugar' : 'lugares'}`,
      text: places ? `${count} del recorrido: ${places}.` : `${count} del recorrido.`,
    };
  }
  const capture = item.capture;
  const state = capture?.state === 'READY' ? 'listo' : capture?.state === 'FAILED' ? 'falló' : 'reconstruyéndose';
  return { ...base, tag: 'Recorrido 3D', text: `${capture?.zone ?? 'General'}: ${state}.` };
}

/**
 * "Mis últimas publicaciones" de Hoy en escritorio: las cinco últimas de la persona en la casa,
 * compactas, con "Corregir" en los avances (lo mismo que ofrece Novedades) y "Ver todo".
 */
@Component({
  selector: 'app-field-recent',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="recent" aria-labelledby="recent-title">
      <div class="head">
        <h2 id="recent-title">Mis últimas publicaciones</h2>
        <a class="ov-link all" [routerLink]="['/hoy', unitId(), 'publicaciones']">Ver todo</a>
      </div>
      @for (row of rows(); track row.id) {
        <div class="row">
          <span class="when">{{ row.when }}</span>
          <div class="body">
            <div class="tags">
              <span class="tag">{{ row.tag }}</span>
              @if (row.change) {
                <span class="tag change ov-num">{{ row.change }}</span>
              }
            </div>
            <p class="text">{{ row.text }}</p>
          </div>
          @if (row.correct; as correct) {
            <a class="ov-link correct" [routerLink]="correct.link" [queryParams]="correct.query">Corregir</a>
          }
        </div>
      } @empty {
        <p class="empty">{{ loaded() ? 'Todavía no publicaste nada en esta casa.' : '' }}</p>
      }
    </section>
  `,
  styles: `
    .recent {
      display: grid;
      padding: var(--ov-space-5);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      padding-bottom: var(--ov-space-2);
    }

    h2 {
      font-size: var(--ov-text-md);
    }

    .row {
      display: flex;
      align-items: flex-start;
      gap: var(--ov-space-4);
      padding: var(--ov-space-3) 0;
      border-top: 1px solid var(--ov-line);
    }

    .when {
      flex: 0 0 3.5rem;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .body {
      display: grid;
      flex: 1;
      gap: var(--ov-space-1);
      min-width: 0;
    }

    .tags {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-1);
    }

    .tag {
      padding: 0 var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .tag.change {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .text,
    .empty {
      font-size: var(--ov-text-sm);
    }

    .empty {
      padding-top: var(--ov-space-2);
      color: var(--ov-ink-muted);
    }
  `,
})
export class FieldRecentComponent {
  readonly items = input.required<ActivityItem[]>();
  readonly loaded = input(false);
  readonly unitId = input.required<string>();
  readonly developmentId = input.required<string>();

  protected rows(): RecentRow[] {
    return this.items().map(item => recentRow(item, this.developmentId()));
  }
}
