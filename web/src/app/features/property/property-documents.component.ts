import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { UnitDocument } from '../../core/api/api.models';
import { DocumentsService } from '../../core/api/documents.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { DOCUMENT_CATEGORY_LABELS } from '../../shared/ui/document-labels';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { PropertyViewStore } from './property-view.store';
import { ShortDatePipe } from '../../shared/time/short-date';

/**
 * Documentos autorizados de la propiedad: los de la unidad y los de la obra. Sólo existe
 * en Mi propiedad; el enlace público no tiene esta pestaña ni forma de pedirlos.
 */
@Component({
  selector: 'app-property-documents',
  imports: [ShortDatePipe, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page ov-page--narrow documents">
      <header class="intro">
        <h2 class="ov-display">Tus documentos</h2>
        <p class="ov-lead">Los que la desarrolladora dejó para vos. Son privados: no aparecen en los enlaces que compartís.</p>
      </header>

      @if (loading()) {
        <app-loading-state label="Cargando documentos…" />
      } @else if (loadError(); as message) {
        <app-error-state tone="neutral" [message]="message" (retry)="load()" />
      } @else if (documents().length === 0) {
        <app-empty-state title="Todavía no hay documentos" message="Cuando la desarrolladora suba el boleto, los planos aprobados o el reglamento, los vas a ver acá." />
      } @else {
        <ul class="list">
          @for (document of documents(); track document.id) {
            <li class="ov-card item">
              <div>
                <p class="ov-eyebrow">{{ labels[document.category] }}{{ document.unitId === null ? ' · de la obra' : '' }}</p>
                <p class="title">{{ document.title }}</p>
                <p class="muted">{{ document.createdAt | shortDate: 'd MMM y' }} · {{ document.originalFilename }}</p>
              </div>
              @if (document.downloadUrl) {
                <a class="ov-btn ov-btn--secondary" [href]="document.downloadUrl" target="_blank" rel="noopener">Abrir</a>
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    .documents {
      display: grid;
      gap: var(--ov-space-6);
      --ov-measure: 48rem;
    }

    .intro {
      display: grid;
      gap: var(--ov-space-3);
    }

    .list {
      display: grid;
      gap: var(--ov-space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .title {
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
    }

    .muted {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }
  `,
})
export class PropertyDocumentsComponent implements OnInit {

  private readonly store = inject(PropertyViewStore);
  private readonly documentsService = inject(DocumentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = DOCUMENT_CATEGORY_LABELS;
  protected readonly documents = signal<UnitDocument[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  private readonly unitId = computed(() => {
    const source = this.store.source();
    return source?.kind === 'private' ? source.unitId : null;
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const unitId = this.unitId();
    if (!unitId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.documentsService.listForUnit(unitId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: documents => {
        // Primero los de la unidad, después los de la obra.
        this.documents.set([...documents].sort((a, b) => Number(a.unitId === null) - Number(b.unitId === null)));
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessageOf(error, 'No se pudieron cargar los documentos.'));
      },
    });
  }
}
