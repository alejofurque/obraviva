import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AssignedHouse, Stage } from '../../core/api/api.models';
import { ImagePipe } from '../../shared/media/variant.pipe';
import { FieldStagesComponent } from './field-stages.component';

/**
 * Al costado de Hoy en escritorio: la casa elegida con su foto y su avance, las etapas en
 * curso con "Publicar" (que abre "Publicar avance" con esa etapa) y "Ver la casa completa".
 * La casa se cambia desde la barra de arriba: acá no hay otro selector.
 */
@Component({
  selector: 'app-field-house-aside',
  imports: [RouterLink, ImagePipe, FieldStagesComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = house();
    <section class="aside" [attr.aria-label]="current.code + ', ' + current.developmentName">
      @if (current.coverUrl) {
        <img class="cover" [src]="current | image: 'cover' : 'thumb'" alt="" />
      } @else {
        <span class="cover empty" aria-hidden="true"></span>
      }
      <div class="content">
        <div>
          <p class="place">{{ current.developmentName }}</p>
          <p class="code">{{ current.code }}</p>
        </div>
        <div class="progress">
          <span class="ov-progress" aria-hidden="true"><span [style.width.%]="current.progressPercent ?? 0"></span></span>
          <span class="ov-num">{{ current.progressPercent ?? 0 }}%</span>
        </div>
        <h2>Etapas en curso</h2>
        @if (stages(); as list) {
          <app-field-stages [stages]="list" [unitId]="current.unitId" [publish]="true" />
        }
        <a class="all" [routerLink]="['/hoy', current.unitId, 'casa']">Ver la casa completa</a>
      </div>
    </section>
  `,
  styles: `
    .aside {
      overflow: hidden;
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .cover {
      display: block;
      width: 100%;
      height: 9rem;
      object-fit: cover;
    }

    .cover.empty {
      background: var(--ov-surface-muted);
    }

    .content {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
    }

    .place {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .code {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .progress {
      display: flex;
      align-items: center;
      gap: var(--ov-space-2);
      font-weight: var(--ov-weight-semibold);
    }

    .progress .ov-progress {
      flex: 1;
    }

    h2 {
      margin-top: var(--ov-space-2);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .all {
      display: inline-flex;
      align-items: center;
      min-height: 3rem;
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }
  `,
})
export class FieldHouseAsideComponent {
  readonly house = input.required<AssignedHouse>();
  /** Las etapas abiertas, ya en orden; nulo mientras cargan. */
  readonly stages = input<Stage[] | null>(null);
}
