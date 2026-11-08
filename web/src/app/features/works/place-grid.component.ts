import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { PlaceCell, PlaceGrid, PlaceRow, monthHead, monthLong, placeMeta } from './place-grid';

export interface PlaceSelection {
  name: string;
  month: string;
}

/**
 * La grilla de "Lugares y 3D": un lugar por fila y un mes por columna. Cada celda dice si
 * hay recorrido 3D listo (negro) o reconstruyéndose (ámbar) y cuántas fotos y videos hay; sin
 * nada, un guion. Tocar una celda abre su detalle debajo.
 */
@Component({
  selector: 'app-place-grid',
  imports: [MatIconModule, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-card wrap">
      <div #wrap class="scroller">
      <table>
        <thead>
          <tr>
            <th scope="col" class="place-col">Lugar</th>
            @for (month of months(); track month) {
              <th scope="col" class="month">{{ head(month) }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of grid().rows; track row.name) {
            <tr [class.chosen]="selected()?.name === row.name">
              <th scope="row" class="place">
                <span class="place-inner">
                <span class="thumb" [class.dark]="!row.thumb">
                  @if (row.thumb; as thumb) {
                    <img [src]="thumb | variant: 'thumb'" alt="" loading="lazy" />
                  } @else {
                    <mat-icon svgIcon="view_in_ar" aria-hidden="true" />
                  }
                </span>
                <span class="place-text">
                  <span class="name">{{ row.name }}@if (row.front) {{{ ' ' }}<span class="front">Frente</span>}</span>
                  <span class="meta">{{ meta(row) }}</span>
                </span>
                </span>
              </th>
              @for (month of months(); track month) {
                @let cell = cellOf(row, month);
                <td>
                  <button type="button" class="cell" [class.empty]="isEmpty(cell)"
                          [class.selected]="selected()?.name === row.name && selected()?.month === month"
                          [attr.aria-pressed]="selected()?.name === row.name && selected()?.month === month"
                          [attr.aria-label]="label(row, month, cell)" (click)="select.emit({ name: row.name, month })">
                    @if (cell.tours.length > 0) { <span class="cube">3D</span> }
                    @if (cell.building.length > 0) { <span class="cube building">3D</span> }
                    @if (cell.media.length > 0) { <span class="count">{{ cell.media.length === 1 ? '1 foto' : cell.media.length + ' fotos' }}</span> }
                    @if (isEmpty(cell)) { <span aria-hidden="true">—</span> }
                  </button>
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
      </div>
      <p class="legend">
        <span><span class="cube">3D</span> Recorrido listo</span>
        <span><span class="cube building">3D</span> Reconstruyendo</span>
        <span>"4 fotos": las fotos y videos de ese lugar ese mes.</span>
        <span>Tocá una celda para ver, mover o borrar lo que tiene.</span>
      </p>
    </div>
  `,
  styles: `
    .wrap {
      overflow: hidden;
    }

    .scroller {
      overflow-x: auto;
    }

    table {
      width: 100%;
      min-width: 44rem;
      border-collapse: collapse;
    }

    th,
    td {
      padding: var(--ov-space-2);
      border-top: 1px solid var(--ov-line);
    }

    thead th {
      border-top: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .place-col,
    .place {
      width: 15rem;
      padding-left: var(--ov-space-5);
      text-align: left;
    }

    .month {
      text-align: center;
    }

    /* El nombre del lugar queda fijo a la izquierda mientras los meses se corren. */
    .place-col,
    .place {
      position: sticky;
      left: 0;
      z-index: 1;
      background: var(--ov-surface);
      box-shadow: 1px 0 0 var(--ov-line);
    }

    tr.chosen .place,
    tr.chosen td {
      background: color-mix(in srgb, var(--ov-accent-soft) 35%, var(--ov-surface));
    }

    .place {
      font-weight: var(--ov-weight-regular);
    }

    .place-inner,
    .place-text {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .place-text {
      flex-direction: column;
      align-items: flex-start;
      gap: 0;
      min-width: 0;
    }

    .thumb {
      display: grid;
      flex: 0 0 3rem;
      place-items: center;
      height: 2.25rem;
      overflow: hidden;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
    }

    /* Sin fotos del lugar: clara, con el ícono del 3D, como en el Resumen y en la vista del comprador. */
    .thumb.dark {
      border: 1px solid var(--ov-line);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    .thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .thumb mat-icon {
      width: 1.25rem;
      height: 1.25rem;
    }

    .name {
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .front {
      margin-left: var(--ov-space-2);
      padding: 0.05rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
    }

    .meta,
    .legend {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .cell {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.2rem;
      width: 100%;
      min-height: 3rem;
      padding: 0;
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      cursor: pointer;
    }

    .cell.empty {
      border-style: dashed;
      background: transparent;
      color: var(--ov-line-strong);
    }

    .cell.selected {
      border: 2px solid var(--ov-accent);
      background: var(--ov-accent-soft);
    }

    .cell:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .cube {
      padding: 0.05rem 0.3rem;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
      font-size: 0.625rem;
      font-weight: var(--ov-weight-semibold);
    }

    .cube.building {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .count {
      font-size: var(--ov-text-xs);
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2) var(--ov-space-5);
      padding: var(--ov-space-3) var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
    }

    .legend > span {
      display: inline-flex;
      align-items: center;
      gap: var(--ov-space-1);
    }

    @media (max-width: 40rem) {
      .place-col,
      .place {
        width: 8.5rem;
        padding-left: var(--ov-space-4);
      }

      .thumb {
        display: none;
      }

      .meta {
        display: none;
      }
    }
  `,
})
export class PlaceGridComponent {
  readonly grid = input.required<PlaceGrid>();
  /** Los meses que se muestran: los más nuevos, salvo que se pidan los anteriores. */
  readonly months = input.required<string[]>();
  readonly selected = input<PlaceSelection | null>(null);
  readonly select = output<PlaceSelection>();

  protected readonly head = monthHead;
  private readonly wrap = viewChild.required<ElementRef<HTMLElement>>('wrap');

  constructor() {
    // En el teléfono la tabla no entra: arranca mostrando los meses más nuevos.
    afterNextRender(() => {
      const wrap = this.wrap().nativeElement;
      wrap.scrollLeft = wrap.scrollWidth;
    });
  }
  protected readonly meta = placeMeta;

  protected cellOf(row: PlaceRow, month: string): PlaceCell {
    return row.cells[this.grid().months.indexOf(month)];
  }

  protected isEmpty(cell: PlaceCell): boolean {
    return cell.tours.length === 0 && cell.building.length === 0 && cell.media.length === 0;
  }

  protected label(row: PlaceRow, month: string, cell: PlaceCell): string {
    const parts: string[] = [];
    if (cell.tours.length > 0) parts.push(cell.tours.length === 1 ? '1 recorrido 3D' : `${cell.tours.length} recorridos 3D`);
    if (cell.building.length > 0) parts.push('reconstruyendo en 3D');
    if (cell.media.length > 0) parts.push(cell.media.length === 1 ? '1 foto o video' : `${cell.media.length} fotos y videos`);
    return `${row.name}, ${monthLong(month)}: ${parts.length > 0 ? parts.join(', ') : 'sin material'}`;
  }
}
