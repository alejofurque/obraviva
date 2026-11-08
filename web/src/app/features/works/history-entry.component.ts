import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MediaAsset, ProgressEntry } from '../../core/api/api.models';
import { MAX_PHOTOS_PER_ENTRY } from '../../shared/media/photo-limit';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { SectorChipComponent } from '../../shared/ui/sector-chip.component';
import { changeChip } from './overview-derivations';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * Un avance de la Historia: el día grande, la
 * etapa, lo que le hizo a la etapa ("35% → 50%"), si vino de un audio, el texto, las fotos con
 * su lugar (y el chip para cambiarlo), quién lo publicó, "Corregir" y "Agregar fotos".
 */
@Component({
  selector: 'app-history-entry',
  imports: [ShortDatePipe, DatePipe, VariantPipe, SectorChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = entry();
    <article class="entry">
      <div class="day" aria-hidden="true">
        <span class="day-number ov-num">{{ current.entryDate | date: 'd' }}</span>
        <span class="weekday">{{ current.entryDate | date: 'EEE' }}</span>
      </div>
      <div class="body">
        <p class="tags">
          <span class="visually-hidden">{{ current.entryDate | date: "d 'de' MMMM" }} ·</span>
          <span class="stage">{{ stageName() ?? generalLabel() }}</span>
          @if (chip(); as change) {
            <span class="change ov-num">{{ change }}</span>
          }
          @if (current.source === 'AUDIO') {
            <span class="muted">· por audio</span>
          } @else if (current.source === 'INITIAL_LOAD') {
            <span class="initial">Carga inicial</span>
          }
          @if (current.version > 1) {
            <span class="muted">· corregido</span>
          }
        </p>
        <p class="description">{{ current.description }}</p>

        @if (photos().length > 0) {
          <ul class="photos">
            @for (photo of photos(); track photo.id) {
              <li class="tile">
                <div class="photo">
                  <img [src]="photo | variant: 'thumb'" [alt]="'Foto del ' + (current.entryDate | shortDate: 'd MMM y')" loading="lazy" />
                  <span class="place" [class.unplaced]="!photo.sector">{{ photo.sector ?? 'Sin lugar' }}</span>
                  @if (canDelete(photo)) {
                    <button type="button" class="remove" (click)="deletePhoto.emit(photo)"
                            [attr.aria-label]="'Borrar foto ' + photo.originalFilename">Borrar</button>
                  }
                </div>
                @if (sectors().length > 0) {
                  <app-sector-chip [sectors]="sectors()" [value]="photo.sector" [disabled]="busyPhotoId() === photo.id"
                                   [label]="'Lugar de ' + photo.originalFilename" (changed)="movePhoto.emit({ photo, sector: $event })" />
                }
              </li>
            }
          </ul>
        }

        <div class="foot">
          @if (current.authorName) {
            <span>{{ current.authorName }}</span>
          }
          <button class="ov-link" type="button" (click)="correct.emit(current)">Corregir</button>
          <label class="ov-link file-link" [class.full]="full()">
            {{ full() ? 'Llegaste al máximo de ' + maxPhotos + ' fotos' : uploading() ? 'Subiendo fotos…' : 'Agregar fotos' }}
            <input type="file" accept="image/*" multiple (change)="addPhotos.emit($event)" [disabled]="uploadsBusy() || full()" />
          </label>
        </div>
      </div>
    </article>
  `,
  styles: `
    .entry {
      display: flex;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .day {
      display: grid;
      flex: 0 0 3.25rem;
      align-content: start;
      justify-items: center;
    }

    .day-number {
      font-size: var(--ov-text-xl);
      font-weight: var(--ov-weight-semibold);
      line-height: 1;
    }

    .weekday,
    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .body {
      display: grid;
      flex: 1;
      gap: var(--ov-space-2);
      min-width: 0;
    }

    .tags {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-1) var(--ov-space-2);
    }

    .stage,
    .change,
    .initial {
      padding: 0.15rem var(--ov-space-2);
      border-radius: var(--ov-radius-sm);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .stage {
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
    }

    /* Gris: no es una alerta, es de dónde viene el avance. */
    .initial {
      background: var(--ov-surface-muted);
      color: var(--ov-ink-muted);
    }

    .change {
      background: var(--ov-surface-muted);
      color: var(--ov-ink);
    }

    .description {
      line-height: 1.5;
    }

    .photos {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .tile {
      display: grid;
      gap: var(--ov-space-1);
      width: 7rem;
    }

    .photo {
      position: relative;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
    }

    .photo img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .place {
      position: absolute;
      bottom: var(--ov-space-1);
      left: var(--ov-space-1);
      max-width: calc(100% - var(--ov-space-2));
      overflow: hidden;
      padding: 0.05rem var(--ov-space-1);
      border-radius: var(--ov-radius-sm);
      background: color-mix(in srgb, var(--ov-ink) 60%, transparent);
      color: var(--ov-on-media);
      font-size: 0.625rem;
      font-weight: var(--ov-weight-semibold);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .place.unplaced {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .remove {
      position: absolute;
      top: var(--ov-space-1);
      right: var(--ov-space-1);
      padding: 0.1rem var(--ov-space-2);
      border: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .foot {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-1) var(--ov-space-4);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .file-link {
      position: relative;
      cursor: pointer;
    }

    .file-link input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .file-link.full,
    .file-link.full input {
      color: var(--ov-ink-muted);
      text-decoration: none;
      cursor: default;
    }

    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
    }

    @media (max-width: 40rem) {
      .entry {
        gap: var(--ov-space-3);
        padding: var(--ov-space-4);
      }

      .day {
        flex-basis: 2.5rem;
      }
    }
  `,
})
export class HistoryEntryComponent {
  readonly entry = input.required<ProgressEntry>();
  readonly stageName = input<string | null>(null);
  readonly generalLabel = input('General');
  readonly photos = input<MediaAsset[]>([]);
  readonly sectors = input<string[]>([]);
  readonly owner = input(false);
  readonly busyPhotoId = input<string | null>(null);
  readonly uploading = input(false);
  readonly uploadsBusy = input(false);

  readonly correct = output<ProgressEntry>();
  readonly addPhotos = output<Event>();
  readonly movePhoto = output<{ photo: MediaAsset; sector: string | null }>();
  readonly deletePhoto = output<MediaAsset>();

  protected readonly chip = computed(() => changeChip(this.entry()));
  protected readonly maxPhotos = MAX_PHOTOS_PER_ENTRY;
  /** Con 10 fotos (los videos no cuentan) no se agregan más: el servidor también lo frena. */
  protected readonly full = computed(() =>
    this.photos().filter(photo => photo.kind === 'PHOTO').length >= MAX_PHOTOS_PER_ENTRY);

  protected canDelete(photo: MediaAsset): boolean {
    return this.owner() || photo.mine;
  }
}
