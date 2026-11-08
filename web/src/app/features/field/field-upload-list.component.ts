import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Una foto o video de un lugar mientras sube, o cortado. */
export interface UploadListItem {
  key: number;
  file: Pick<File, 'name'>;
  percent: number;
  state: 'uploading' | 'failed';
  error: string | null;
}

/**
 * Las fotos y videos de un lugar que se están subiendo en el recorrido del día, cada uno con su
 * progreso; uno cortado dice por qué y ofrece Reintentar o Quitar. La subida la maneja la pantalla.
 */
@Component({
  selector: 'app-field-upload-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="media-uploads">
      @for (item of items(); track item.key) {
        <li [attr.data-state]="item.state">
          <span class="media-name">{{ item.file.name }}</span>
          @if (item.state === 'uploading') {
            <span class="ov-num">{{ item.percent }}%</span>
            <span class="ov-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="item.percent"
                  [attr.aria-label]="'Subida de ' + item.file.name"><span [style.width.%]="item.percent"></span></span>
          } @else {
            <span class="problem">{{ item.error }}</span>
            <span class="row-actions">
              <button type="button" class="ov-link" (click)="retry.emit(item.key)">Reintentar</button>
              <button type="button" class="ov-link" (click)="remove.emit(item.key)">Quitar</button>
            </span>
          }
        </li>
      }
    </ul>
  `,
  styles: `
    .media-uploads {
      display: grid;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--ov-text-sm);
    }

    li {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--ov-space-1) var(--ov-space-3);
    }

    .ov-progress,
    .problem,
    .row-actions {
      grid-column: 1 / -1;
    }

    .media-name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .row-actions {
      display: flex;
      gap: var(--ov-space-4);
    }

    .ov-link {
      min-height: 3rem;
    }

    .problem::before {
      content: '⚠ ';
      color: var(--ov-status-delayed);
    }
  `,
})
export class FieldUploadListComponent {
  readonly items = input.required<UploadListItem[]>();
  readonly retry = output<number>();
  readonly remove = output<number>();
}
