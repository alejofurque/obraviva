import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProjectComparison } from '../../core/api/api.models';
import { ComparisonsService } from '../../core/api/comparisons.service';
import { ImagePipe } from '../../shared/media/variant.pipe';

/**
 * "Proyecto y realidad" en la ficha: las comparaciones de la casa (un render y una foto de la
 * obra desde el mismo lugar), mitad y mitad, y "Agregar", que lleva a la pantalla donde se
 * arman. Es un resumen: cargar y editar sigue en su pantalla, no se duplica acá.
 */
@Component({
  selector: 'app-comparisons-summary',
  imports: [ImagePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ov-card block" aria-labelledby="comparisons-title">
      <div class="head">
        <h3 id="comparisons-title">Proyecto y realidad</h3>
        <a class="ov-link" [routerLink]="link()">{{ mine().length > 0 ? 'Ver y agregar' : 'Agregar' }}</a>
      </div>
      <p class="muted">Un render y una foto de la obra desde el mismo lugar.</p>
      @if (mine().length > 0) {
        <ul class="tiles">
          @for (comparison of mine(); track comparison.id) {
            <li>
              <a class="tile" [routerLink]="link()" [attr.aria-label]="comparison.title + (comparison.complete ? '' : ', falta ' + missing(comparison))">
                <span class="half">
                  @if (comparison.renderUrl) { <img [src]="comparison | image: 'render' : 'thumb'" alt="" loading="lazy" /> }
                </span>
                <span class="half">
                  @if (comparison.photoUrl; as url) { <img [src]="url" alt="" loading="lazy" /> }
                </span>
              </a>
              <span class="title">{{ comparison.title }}</span>
              @if (!comparison.complete) {
                <span class="missing">Falta {{ missing(comparison) }}: el comprador todavía no la ve.</span>
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .block {
      display: grid;
      gap: var(--ov-space-3);
      padding: var(--ov-space-5);
    }

    .head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--ov-space-3);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .muted,
    .title {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .tiles {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: grid;
      gap: var(--ov-space-1);
    }

    .tile {
      display: grid;
      grid-template-columns: 1fr 1fr;
      height: 6.25rem;
      overflow: hidden;
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface-muted);
    }

    .half img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .missing {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-xs);
    }
  `,
})
export class ComparisonsSummaryComponent implements OnInit {
  readonly developmentId = input.required<string>();
  readonly unitId = input.required<string>();
  readonly link = input.required<string[]>();

  private readonly comparisons = inject(ComparisonsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly all = signal<ProjectComparison[]>([]);
  protected readonly mine = computed(() => this.all().filter(comparison => comparison.unitId === this.unitId()));

  ngOnInit(): void {
    // Es un resumen: si no carga, la ficha se usa igual y "Agregar" lleva a la pantalla.
    this.comparisons.list(this.developmentId()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: comparisons => this.all.set(comparisons),
      error: () => this.all.set([]),
    });
  }

  protected missing(comparison: ProjectComparison): string {
    if (!comparison.renderUrl && !comparison.photoUrl) return 'el render y la foto';
    return comparison.renderUrl ? 'la foto' : 'el render';
  }
}
