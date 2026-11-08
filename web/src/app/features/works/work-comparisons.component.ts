import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin } from 'rxjs';
import { MediaAsset, ProjectComparison } from '../../core/api/api.models';
import { ComparisonsService } from '../../core/api/comparisons.service';
import { ProgressService } from '../../core/api/progress.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { BackLinkDirective } from '../../core/routing/back-navigation';
import { ImagePipe, VariantPipe } from '../../shared/media/variant.pipe';
import { CompareSliderComponent } from '../../shared/ui/compare-slider.component';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { injectWorkScope } from './work-scope';
import { WorkStore } from './work.store';
import { ShortDatePipe } from '../../shared/time/short-date';

const RENDER_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_RENDER_BYTES = 20 * 1024 * 1024;

/**
 * Proyecto y realidad: el dueño sube el render de un lugar y elige la foto de avance que
 * lo muestra hoy. El comprador lo ve con un deslizador en Mi propiedad y en el enlace
 * compartido, sólo cuando tiene las dos imágenes.
 *
 * <p>La foto se vuelve a elegir cuando la obra avanza: la comparación sigue siendo la
 * misma, y el render queda fijo porque es el proyecto.
 */
@Component({
  selector: 'app-work-comparisons',
  imports: [ShortDatePipe, ImagePipe, FormsModule, BackLinkDirective, CompareSliderComponent, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow comparisons">
      <header class="intro">
        <a class="back ov-hit" [appBack]="scope.link('historia')">Volver</a>
        <h2>Proyecto y realidad</h2>
        <p class="ov-lead">
          Subí el render de un lugar y elegí la foto de avance que lo muestra hoy, en la misma perspectiva. El comprador
          los compara con un deslizador. Cuando la obra avance, cambiá la foto.
        </p>
      </header>

      <form class="ov-card create" (ngSubmit)="create()">
        <div class="ov-field">
          <label class="ov-eyebrow" for="new-title">Qué lugar</label>
          <input id="new-title" class="ov-input" name="title" [(ngModel)]="newTitle" maxlength="120"
                 placeholder="Por ejemplo: Living desde la ventana" />
        </div>
        <button class="ov-btn ov-btn--primary" type="submit" [disabled]="busy() || !newTitle.trim()">Agregar</button>
      </form>

      @if (actionError(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }

      @if (loading()) {
        <app-loading-state label="Cargando las comparaciones…" />
      } @else if (loadError(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (comparisons().length === 0) {
        <app-empty-state title="Todavía no hay comparaciones"
          message="Empezá por la fachada o el living: son los lugares que más se comparan con el render." />
      } @else {
        <ul class="list">
          @for (comparison of comparisons(); track comparison.id) {
            <li class="ov-card item">
              <div class="item-head">
                <div>
                  <p class="item-title">{{ comparison.title }}</p>
                  <p class="muted">{{ scopeLabel(comparison) }}</p>
                </div>
                <span class="ov-pill" [class.ov-pill--accent]="comparison.complete" [class.ov-pill--outline]="!comparison.complete">
                  {{ comparison.complete ? 'Visible para el comprador' : missingLabel(comparison) }}
                </span>
              </div>

              @if (comparison.renderUrl && comparison.photoUrl) {
                <app-compare-slider class="preview" [beforeUrl]="(comparison | image: 'render' : 'screen') ?? ''" [afterUrl]="comparison.photoUrl" />
              } @else {
                <div class="halves">
                  <div class="half">
                    @if (comparison.renderUrl) {
                      <img [src]="comparison | image: 'render' : 'screen'" alt="Render" />
                    } @else {
                      <p class="muted">Sin render</p>
                    }
                  </div>
                  <div class="half">
                    @if (comparison.photoUrl) {
                      <img [src]="comparison.photoUrl" alt="Foto de obra" />
                    } @else {
                      <p class="muted">Sin foto de obra</p>
                    }
                  </div>
                </div>
              }

              <div class="row">
                <label class="ov-btn ov-btn--secondary file">
                  {{ uploadingId() === comparison.id ? 'Subiendo…' : (comparison.renderUrl ? 'Cambiar render' : 'Subir render') }}
                  <input type="file" accept="image/jpeg,image/png,image/webp" (change)="uploadRender(comparison, $event)" [disabled]="busy()" />
                </label>
                <button class="ov-btn ov-btn--secondary" type="button" (click)="togglePicker(comparison.id)" [disabled]="busy()">
                  {{ pickingId() === comparison.id ? 'Cerrar fotos' : (comparison.photoUrl ? 'Cambiar foto de obra' : 'Elegir foto de obra') }}
                </button>
                <button class="ov-link danger" type="button" (click)="remove(comparison)" [disabled]="busy()">Borrar</button>
              </div>

              @if (pickingId() === comparison.id) {
                @if (photos().length === 0) {
                  <p class="muted">La obra todavía no tiene fotos de avance. Publicá un avance con fotos en la Historia.</p>
                } @else {
                  <ul class="photos" aria-label="Fotos de avance">
                    @for (photo of photos(); track photo.id) {
                      <li>
                        <button type="button" class="photo" [class.chosen]="photo.id === comparison.photoMediaId"
                                [attr.aria-pressed]="photo.id === comparison.photoMediaId" (click)="choosePhoto(comparison, photo)">
                          <img [src]="photo | variant: 'thumb'" [alt]="'Foto del ' + (photo.createdAt | shortDate: 'd MMM y')" loading="lazy" />
                          <span class="ov-num">{{ photo.createdAt | shortDate: 'd MMM y' }}</span>
                        </button>
                      </li>
                    }
                  </ul>
                }
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .comparisons {
      display: grid;
      gap: var(--ov-space-5);
      --ov-measure: 60rem;
      padding-top: var(--ov-space-6);
    }

    .intro {
      display: grid;
      gap: var(--ov-space-2);
    }

    .back {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-decoration: none;
    }

    .back::before {
      content: '‹ ';
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .create {
      display: grid;
      gap: var(--ov-space-4);
      align-items: end;
      padding: var(--ov-space-5);
    }

    .list {
      display: grid;
      gap: var(--ov-space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .item {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .item-head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    .item-title {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .preview {
      max-width: 40rem;
    }

    .halves {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--ov-space-3);
      max-width: 40rem;
    }

    .half {
      display: grid;
      place-items: center;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      border: 1px dashed var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
    }

    .half img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .file {
      position: relative;
      overflow: hidden;
    }

    .file input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }

    .file:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .danger {
      color: var(--ov-danger);
    }

    .photos {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .photo {
      display: grid;
      gap: var(--ov-space-1);
      width: 100%;
      padding: var(--ov-space-1);
      border: 2px solid transparent;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
      color: var(--ov-ink-muted);
      font: inherit;
      font-size: var(--ov-text-xs);
      cursor: pointer;
    }

    .photo img {
      width: 100%;
      aspect-ratio: 4 / 3;
      border-radius: var(--ov-radius-sm);
      object-fit: cover;
    }

    .photo.chosen {
      border-color: var(--ov-accent);
      color: var(--ov-accent);
    }

    .photo:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    @media (min-width: 48rem) {
      .create {
        grid-template-columns: 1fr 1fr auto;
      }
    }
  `,
})
export class WorkComparisonsComponent implements OnInit {

  protected readonly store = inject(WorkStore);
  private readonly comparisonsService = inject(ComparisonsService);
  private readonly progress = inject(ProgressService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly comparisons = signal<ProjectComparison[]>([]);
  private readonly media = signal<MediaAsset[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly pickingId = signal<string | null>(null);
  protected readonly uploadingId = signal<string | null>(null);

  protected newTitle = '';

  /** Sólo fotos de avance, las más nuevas primero: lo que se compara con el render es el presente. */
  protected readonly photos = computed(() => this.media()
    .filter(asset => asset.kind === 'PHOTO' && asset.status === 'AVAILABLE' && this.scope.owns(asset.unitId))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt)));

  protected readonly scope = injectWorkScope();

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      comparisons: this.comparisonsService.list(this.developmentId),
      media: this.progress.media(this.developmentId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ comparisons, media }) => {
          this.comparisons.set(comparisons.filter(comparison => this.scope.owns(comparison.unitId)));
          this.media.set(media);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(errorMessageOf(error, 'No se pudieron cargar las comparaciones.'));
        },
      });
  }

  protected scopeLabel(comparison: ProjectComparison): string {
    return comparison.unitId === null ? 'La ven todos los compradores del desarrollo' : 'La ve el comprador de esta casa';
  }

  protected missingLabel(comparison: ProjectComparison): string {
    if (!comparison.renderUrl && !comparison.photoUrl) {
      return 'Falta el render y la foto';
    }
    return comparison.renderUrl ? 'Falta la foto de obra' : 'Falta el render';
  }

  protected create(): void {
    const title = this.newTitle.trim();
    if (!title) {
      return;
    }
    this.run(this.comparisonsService.create(this.developmentId, title, this.scope.unitId()), created => {
      this.newTitle = '';
      this.comparisons.update(list => [...list, created]);
    }, 'No se pudo crear la comparación.');
  }

  protected uploadRender(comparison: ProjectComparison, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (!RENDER_TYPES.includes(file.type)) {
      this.actionError.set('El render tiene que ser una imagen JPG, PNG o WebP.');
      return;
    }
    if (file.size > MAX_RENDER_BYTES) {
      this.actionError.set('El render pesa más de 20 MB. Exportalo con menos resolución.');
      return;
    }
    this.uploadingId.set(comparison.id);
    this.run(this.comparisonsService.uploadRender(comparison.id, file), updated => {
      this.uploadingId.set(null);
      this.replace(updated);
    }, 'No se pudo subir el render.', () => this.uploadingId.set(null));
  }

  protected togglePicker(comparisonId: string): void {
    this.pickingId.set(this.pickingId() === comparisonId ? null : comparisonId);
  }

  protected choosePhoto(comparison: ProjectComparison, photo: MediaAsset): void {
    this.run(this.comparisonsService.update({
      id: comparison.id, title: comparison.title, unitId: comparison.unitId, photoMediaId: photo.id,
    }), updated => {
      this.replace(updated);
      this.pickingId.set(null);
    }, 'No se pudo elegir la foto.');
  }

  protected remove(comparison: ProjectComparison): void {
    if (!window.confirm(`¿Borrar la comparación "${comparison.title}"? Deja de verla el comprador.`)) {
      return;
    }
    this.run(this.comparisonsService.remove(comparison.id), () => {
      this.comparisons.update(list => list.filter(item => item.id !== comparison.id));
    }, 'No se pudo borrar la comparación.');
  }

  private replace(updated: ProjectComparison): void {
    this.comparisons.update(list => list.map(item => (item.id === updated.id ? updated : item)));
  }

  private run<T>(request: Observable<T>, done: (value: T) => void, fallback: string, onError?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: value => {
        this.busy.set(false);
        done(value);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        onError?.();
        this.actionError.set(errorMessageOf(error, fallback));
      },
    });
  }
}
