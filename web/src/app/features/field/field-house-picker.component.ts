import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChild } from '@angular/core';
import { AssignedHouse } from '../../core/api/api.models';
import { ImagePipe } from '../../shared/media/variant.pipe';

/**
 * La casa de Hoy como una tarjeta: foto, desarrollo, casa y avance. Con más de una casa, toda la
 * tarjeta es un botón que abre la lista para cambiarla; con una sola, es sólo la tarjeta.
 */
@Component({
  selector: 'app-field-house-picker',
  imports: [ImagePipe, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = house();
    <ng-template #card>
      @if (current.coverUrl) {
        <img class="cover" [src]="current | image: 'cover' : 'thumb'" alt="" />
      } @else {
        <span class="cover empty" aria-hidden="true"></span>
      }
      <span class="text">
        <span class="place">{{ current.developmentName }}</span>
        <span class="code">{{ current.code }}</span>
        <span class="progress">
          <span class="ov-progress" aria-hidden="true"><span [style.width.%]="current.progressPercent ?? 0"></span></span>
          <span class="ov-num">{{ current.progressPercent ?? 0 }}%</span>
        </span>
      </span>
    </ng-template>

    @if (houses().length > 1) {
      <button type="button" class="house" [attr.aria-label]="current.code + ', ' + current.developmentName + '. Cambiar de casa'"
              (click)="open()">
        <ng-container [ngTemplateOutlet]="card" />
        <span class="change" aria-hidden="true">Cambiar</span>
      </button>
    } @else {
      <div class="house"><ng-container [ngTemplateOutlet]="card" /></div>
    }

    @if (houses().length > 1) {
    <dialog #dialog class="sheet" aria-labelledby="picker-title">
      <h2 id="picker-title">¿En qué casa estás?</h2>
      <ul class="options">
        @for (option of houses(); track option.unitId) {
          <li>
            <button type="button" class="option" [class.active]="option.unitId === current.unitId"
                    [attr.aria-current]="option.unitId === current.unitId ? 'true' : null" (click)="pick(option)">
              <span class="code">{{ option.code }}</span>
              <span class="place">{{ option.developmentName }} · {{ option.progressPercent ?? 0 }}%</span>
            </button>
          </li>
        }
      </ul>
      <button type="button" class="ov-btn ov-btn--secondary ov-btn--block" (click)="close()">Cerrar</button>
    </dialog>
    }
  `,
  styles: `
    .house {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
      width: 100%;
      min-height: 5.5rem;
      padding: var(--ov-space-3);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      text-align: left;
    }

    button.house {
      cursor: pointer;
    }

    .cover {
      flex: 0 0 4rem;
      height: 4rem;
      border-radius: var(--ov-radius-md);
      object-fit: cover;
    }

    .cover.empty {
      background: var(--ov-surface-muted);
    }

    .text {
      display: grid;
      flex: 1;
      gap: 2px;
      min-width: 0;
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
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .progress .ov-progress {
      flex: 1;
    }

    .change {
      align-self: center;
      color: var(--ov-accent);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .sheet {
      width: min(28rem, calc(100vw - 2 * var(--ov-gutter)));
      padding: var(--ov-space-5);
      border: 0;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      color: var(--ov-ink);
    }

    .sheet::backdrop {
      background: color-mix(in srgb, var(--ov-ink) 40%, transparent);
    }

    h2 {
      margin: 0 0 var(--ov-space-4);
      font-size: var(--ov-text-lg);
    }

    .options {
      display: grid;
      gap: var(--ov-space-2);
      margin: 0 0 var(--ov-space-4);
      padding: 0;
      list-style: none;
    }

    .option {
      display: grid;
      gap: 2px;
      width: 100%;
      min-height: 3.5rem;
      padding: var(--ov-space-3) var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .option.active {
      border: 2px solid var(--ov-ink);
    }
  `,
})
export class FieldHousePickerComponent {
  readonly house = input.required<AssignedHouse>();
  readonly houses = input.required<AssignedHouse[]>();
  readonly picked = output<AssignedHouse>();

  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('dialog');

  protected open(): void {
    this.dialog()?.nativeElement.showModal?.();
  }

  protected close(): void {
    this.dialog()?.nativeElement.close?.();
  }

  protected pick(house: AssignedHouse): void {
    this.picked.emit(house);
    this.close();
  }
}
