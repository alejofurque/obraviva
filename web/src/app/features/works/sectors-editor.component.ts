import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { SectorChangePayload, SectorView } from '../../core/api/api.models';
import { UnitsService } from '../../core/api/units.service';
import { FieldHouseStore } from '../field/field-house.store';
import { errorMessageOf } from '../../shared/http/error-message';

/** La propuesta para una casa: el frente y los ambientes que más cambian a la vista. */
export const SUGGESTED_SECTORS = ['Frente', 'Living y cocina', 'Dormitorio principal', 'Baño'];
const MAX_SECTORS = 12;

/** Agrega un sector sin repetir (sin importar mayúsculas) y respetando el tope. */
export function addSector(sectors: string[], name: string): string[] {
  const clean = name.trim().replace(/\s+/g, ' ');
  if (!clean || sectors.length >= MAX_SECTORS || sectors.some(sector => sector.toLowerCase() === clean.toLowerCase())) {
    return sectors;
  }
  return [...sectors, clean];
}

/** Mueve un sector un lugar hacia arriba o hacia abajo. */
export function moveSector<T>(sectors: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta;
  if (target < 0 || target >= sectors.length) {
    return sectors;
  }
  const next = [...sectors];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

/**
 * Una fila del editor. {@code previousName} es el sector que era: cambiar el texto de una
 * fila es un renombre, que lleva sus fotos y recorridos al nombre nuevo, no quitar uno y
 * agregar otro. Una fila nueva no tiene.
 */
export interface SectorDraft {
  name: string;
  previousName: string | null;
  front: boolean;
}

export function draftsFrom(views: SectorView[]): SectorDraft[] {
  return views.map(view => ({ name: view.name, previousName: view.name, front: view.front }));
}

/** Agrega una fila nueva sin repetir nombres (sin importar mayúsculas) y respetando el tope. */
export function addDraft(drafts: SectorDraft[], name: string, front = false): SectorDraft[] {
  const clean = name.trim().replace(/\s+/g, ' ');
  if (!clean || drafts.length >= MAX_SECTORS || drafts.some(draft => draft.name.trim().toLowerCase() === clean.toLowerCase())) {
    return drafts;
  }
  const added = { name: clean, previousName: null, front };
  return front ? [added, ...drafts.map(draft => ({ ...draft, front: false }))] : [...drafts, added];
}

/** El frente es uno solo: marcar una fila desmarca las demás. */
export function markFront(drafts: SectorDraft[], index: number): SectorDraft[] {
  return drafts.map((draft, i) => ({ ...draft, front: i === index }));
}

/**
 * Qué sugerir si ninguna fila es el frente: marcar la que se llama "Frente", o agregar una
 * ya marcada. Nulo si ya hay un frente. La portada viva y la comparación de la casa se
 * apoyan en esa marca.
 */
export function frontSuggestion(drafts: SectorDraft[]): { kind: 'mark'; index: number } | { kind: 'add' } | null {
  if (drafts.some(draft => draft.front)) {
    return null;
  }
  const index = drafts.findIndex(draft => draft.name.trim().toLowerCase() === 'frente');
  return index >= 0 ? { kind: 'mark', index } : { kind: 'add' };
}

/** Lo que se guarda: cada fila con su nombre limpio, el que tenía y su marca. */
export function sectorChanges(drafts: SectorDraft[]): SectorChangePayload[] {
  return drafts
    .filter(draft => draft.name.trim() !== '')
    .map(draft => ({ name: draft.name.trim().replace(/\s+/g, ' '), previousName: draft.previousName, front: draft.front }));
}

/**
 * Edición de los sectores de una casa: los lugares que se siguen mes a mes en fotos, videos
 * y recorridos 3D. Lo usa el dueño desde el bloque "Sectores" de Recorridos 3D. Cambiar el
 * texto de una fila la renombra con todo su material; uno de los sectores es el frente.
 */
@Component({
  selector: 'app-sectors-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="editor">
        <ol class="list">
          @for (sector of draft(); track $index; let i = $index; let first = $first; let last = $last) {
            <li class="item">
              <span class="ov-num order">{{ i + 1 }}</span>
              <span class="name">
                <input class="ov-input name-input" maxlength="80" [value]="sector.name"
                       [attr.aria-label]="'Nombre del sector ' + (i + 1)" (input)="rename(i, $any($event.target).value)" />
                @if (sector.previousName && sector.previousName !== sector.name.trim()) {
                  <span class="was">Antes: {{ sector.previousName }}. Sus fotos y recorridos pasan al nombre nuevo.</span>
                }
              </span>
              <label class="front">
                <input type="radio" name="front-sector" [checked]="sector.front" (change)="markFront(i)" />
                Frente
              </label>
              <span class="tools">
                <button type="button" class="tool" [disabled]="first" (click)="move(i, -1)" [attr.aria-label]="'Subir ' + sector.name">Subir</button>
                <button type="button" class="tool" [disabled]="last" (click)="move(i, 1)" [attr.aria-label]="'Bajar ' + sector.name">Bajar</button>
                <button type="button" class="tool" (click)="remove(i)" [attr.aria-label]="'Quitar ' + sector.name">Quitar</button>
              </span>
            </li>
          }
        </ol>
        @if (draft().length > 0 && suggestion(); as hint) {
          <div class="front-hint" role="note">
            <p>Ningún sector está marcado como frente. La portada viva de la casa y la comparación con el proyecto se arman con él.</p>
            @if (hint.kind === 'mark') {
              <button type="button" class="ov-link" (click)="markFront(hint.index)">Marcar "{{ draft()[hint.index].name }}" como frente</button>
            } @else {
              <button type="button" class="ov-link" (click)="addFront()">Agregar "Frente" y marcarlo</button>
            }
          </div>
        }
        <form class="add" (submit)="$event.preventDefault(); add(newSector.value); newSector.value = ''">
          <label class="ov-visually-hidden" for="new-sector">Nuevo sector</label>
          <input #newSector id="new-sector" class="ov-input" maxlength="80" placeholder="Por ejemplo: Garage, Planta alta" />
          <button type="submit" class="ov-btn ov-btn--secondary">Agregar</button>
        </form>
        @if (draft().length === 0 && !loading()) {
          <button type="button" class="ov-link" (click)="useSuggested()">Usar los sugeridos: {{ suggested.join(', ') }}</button>
        }
        <p class="muted">
          Un sector puede seguirse sólo con fotos y videos. Si se graba en 3D, una planta entera entra en un video de tres
          minutos, pero sale con menos detalle que un ambiente, y con el servicio real cada video cuesta un crédito por fecha.
        </p>
        @if (error(); as message) {
          <p class="ov-error" role="alert">{{ message }}</p>
        }
        <div class="actions">
          <button type="button" class="ov-btn ov-btn--primary" [disabled]="saving() || loading()" (click)="save()">
            {{ saving() ? 'Guardando…' : 'Guardar sectores' }}
          </button>
          <button type="button" class="ov-link" (click)="closed.emit()">Cancelar</button>
        </div>
    </div>
  `,
  styles: `
    .editor {
      display: grid;
      gap: var(--ov-space-4);
    }

    .muted {
      max-width: var(--ov-reading-max);
      margin-top: var(--ov-space-1);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .item {
      display: grid;
      grid-template-columns: 1.5rem minmax(0, 1fr) auto auto;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 3.5rem;
      padding: var(--ov-space-2) 0;
      border-bottom: 1px solid var(--ov-line);
    }

    .order {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .name {
      display: grid;
      gap: var(--ov-space-1);
    }

    .name-input {
      min-height: 2.75rem;
    }

    .was {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .front {
      display: flex;
      align-items: center;
      gap: var(--ov-space-1);
      min-height: 2.75rem;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .front input {
      width: 1.1rem;
      height: 1.1rem;
      accent-color: var(--ov-accent);
    }

    .tools {
      display: flex;
      gap: var(--ov-space-1);
    }

    .tool {
      min-height: 2.75rem;
      padding: 0 var(--ov-space-3);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .tool:disabled {
      opacity: 0.4;
      cursor: default;
    }

    .front-hint {
      display: grid;
      gap: var(--ov-space-2);
      padding: var(--ov-space-3) var(--ov-space-4);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
      font-size: var(--ov-text-sm);
    }

    .front-hint .ov-link {
      justify-self: start;
    }

    .add {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: var(--ov-space-3);
    }

    .actions {
      display: flex;
      align-items: center;
      gap: var(--ov-space-4);
    }

    /* Teléfono: el nombre ocupa la fila; la marca y las herramientas van debajo. */
    @media (max-width: 40rem) {
      .item {
        grid-template-columns: 1.5rem minmax(0, 1fr);
      }

      .front,
      .tools {
        grid-column: 2;
      }
    }
  `,
})
export class SectorsEditorComponent implements OnInit {

  readonly unitId = input.required<string>();
  /** Los sectores actuales, mientras llega el detalle con la marca del frente. */
  readonly sectors = input.required<string[]>();
  /** Los sectores guardados, en su orden. */
  readonly saved = output<string[]>();
  readonly closed = output<void>();

  private readonly units = inject(UnitsService);
  private readonly field = inject(FieldHouseStore);

  protected readonly suggested = SUGGESTED_SECTORS;
  protected readonly draft = signal<SectorDraft[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected suggestion(): ReturnType<typeof frontSuggestion> {
    return frontSuggestion(this.draft());
  }

  ngOnInit(): void {
    this.draft.set(this.sectors().map(name => ({ name, previousName: name, front: false })));
    this.units.sectorDetail(this.unitId()).subscribe({
      next: views => {
        this.draft.set(draftsFrom(views));
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo saber cuál es el frente de la casa.'));
      },
    });
  }

  protected rename(index: number, name: string): void {
    this.draft.update(drafts => drafts.map((draft, i) => i === index ? { ...draft, name } : draft));
  }

  protected add(name: string): void {
    this.draft.update(drafts => addDraft(drafts, name));
  }

  protected addFront(): void {
    this.draft.update(drafts => addDraft(drafts, 'Frente', true));
  }

  protected useSuggested(): void {
    this.draft.set(this.suggested.map((name, i) => ({ name, previousName: null, front: i === 0 })));
  }

  protected markFront(index: number): void {
    this.draft.update(drafts => markFront(drafts, index));
  }

  protected move(index: number, delta: -1 | 1): void {
    this.draft.update(drafts => moveSector(drafts, index, delta));
  }

  protected remove(index: number): void {
    this.draft.update(drafts => drafts.filter((_, i) => i !== index));
  }

  protected save(): void {
    this.saving.set(true);
    this.error.set(null);
    this.units.saveSectors(this.unitId(), sectorChanges(this.draft())).subscribe({
      next: sectors => {
        this.saving.set(false);
        this.field.invalidate();
        this.saved.emit(sectors);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.error.set(errorMessageOf(error, 'No se pudieron guardar los sectores.'));
      },
    });
  }
}
