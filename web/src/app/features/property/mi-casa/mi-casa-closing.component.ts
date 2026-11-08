import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { PublicUnitView, UnitDocument } from '../../../core/api/api.models';
import { DocumentsService } from '../../../core/api/documents.service';
import { houseFacts } from '../property-derivations';

/** Cuántos documentos se nombran en la tarjeta antes de "Ver todos". */
const DOCUMENTS = 3;

/**
 * El cierre de "Mi casa": cómo es la casa (con el acceso a planos y el barrio), sus documentos
 * y "Mostrásela a tu familia". En el enlace público no hay documentos ni compartir: sólo la
 * casa. Una tarjeta sin datos no aparece.
 */
@Component({
  selector: 'app-mi-casa-closing',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="closing" aria-label="Tu casa, tus documentos y compartir">
      @if (facts().length > 0 || view().details.modelName) {
        <div class="card">
          <p class="label">Tu casa</p>
          <p class="model">{{ view().details.modelName ?? view().unitCode }}</p>
          @if (facts().length > 0) {
            <dl class="facts">
              @for (fact of facts(); track fact.label) {
                <div><dd class="ov-num">{{ fact.value }}</dd><dt>{{ fact.label }}</dt></div>
              }
            </dl>
          }
          <a class="ov-link strong" [routerLink]="link('casa')" queryParamsHandling="preserve">Planos y el barrio</a>
        </div>
      }

      @if (!isPublic() && documents().length > 0) {
        <div class="card">
          <p class="label">Tus documentos</p>
          @for (document of documents().slice(0, limit); track document.id) {
            <a class="document" [href]="document.downloadUrl" target="_blank" rel="noopener">
              {{ document.title }}<span class="kind">{{ extension(document) }}</span>
            </a>
          }
          @if (documents().length > limit) {
            <a class="ov-link strong" [routerLink]="link('documentos')" queryParamsHandling="preserve">Ver todos</a>
          }
        </div>
      }

      @if (!isPublic()) {
        <div class="card share">
          <p class="ov-display share-title">Mostrásela a tu familia</p>
          <p class="share-text">Creá un enlace para que vean cómo avanza, sin cuenta y sin datos personales. Lo podés cortar cuando quieras.</p>
          <a class="ov-btn ov-btn--primary" [routerLink]="link('compartir')" queryParamsHandling="preserve">Crear enlace</a>
        </div>
      }
    </section>
  `,
  styles: `
    .closing {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
      gap: var(--ov-space-4);
    }

    .card {
      display: grid;
      align-content: start;
      gap: var(--ov-space-3);
      padding: var(--ov-space-6);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-md);
      background: var(--ov-surface);
    }

    .label {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .model {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .facts {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-3) var(--ov-space-2);
      margin: 0;
    }

    .facts dd {
      margin: 0;
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .facts dt {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .document {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      min-height: 2.75rem;
      border-top: 1px solid var(--ov-line);
      color: inherit;
      text-decoration: none;
    }

    .kind {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
    }

    .share {
      border-color: transparent;
      background: var(--ov-accent-soft);
    }

    .share-title {
      font-size: var(--ov-text-lg);
    }

    .share-text {
      line-height: 1.55;
    }

    .share .ov-btn {
      justify-self: start;
      min-height: 2.75rem;
    }
  `,
})
export class MiCasaClosingComponent implements OnInit {
  readonly view = input.required<PublicUnitView>();
  readonly isPublic = input(false);
  readonly unitId = input<string | null>(null);
  readonly basePath = input.required<string[]>();

  private readonly documentsService = inject(DocumentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly documents = signal<UnitDocument[]>([]);
  protected readonly limit = DOCUMENTS;
  protected readonly facts = computed(() => houseFacts(this.view().details)
    .filter(fact => ['Cubiertos', 'Dormitorio', 'Dormitorios', 'Baño', 'Baños'].includes(fact.label))
    .slice(0, 3));

  ngOnInit(): void {
    const unitId = this.unitId();
    if (this.isPublic() || !unitId) {
      return;
    }
    // Los documentos son apoyo: si no cargan, la tarjeta no aparece.
    this.documentsService.listForUnit(unitId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: documents => this.documents.set(documents.filter(document => document.available && document.downloadUrl)),
      error: () => this.documents.set([]),
    });
  }

  protected link(path: string): string[] {
    return [...this.basePath(), path];
  }

  protected extension(document: UnitDocument): string {
    const match = /\.([a-z0-9]+)$/i.exec(document.originalFilename);
    return match ? match[1].toUpperCase() : '';
  }
}
