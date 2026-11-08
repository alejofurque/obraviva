import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { DocumentCategory, UnitDocument } from '../../core/api/api.models';
import { DocumentsService } from '../../core/api/documents.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { DOCUMENT_CATEGORIES, DOCUMENT_CATEGORY_LABELS } from '../../shared/ui/document-labels';
import { ShortDatePipe } from '../../shared/time/short-date';

const ACCEPTED_TYPES = ['application/pdf', 'image/jpeg', 'image/png'];
const MAX_DOCUMENT_BYTES = 25 * 1024 * 1024;

/**
 * Documentos autorizados de una unidad o, con {@code unitId} nulo, de toda la obra.
 *
 * <p>Los ve el comprador en Mi propiedad: los de su unidad y los de la obra. Son privados:
 * no salen por los enlaces que se comparten y el encargado no los ve.
 */
@Component({
  selector: 'app-documents-manager',
  imports: [ShortDatePipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="documents">
      @if (documents().length === 0) {
        <p class="hint">{{ unitId() ? 'Esta casa todavía no tiene documentos.' : 'La obra todavía no tiene documentos para todos los compradores.' }}</p>
      } @else {
        <ul class="list">
          @for (document of documents(); track document.id) {
            <li class="row">
              <div>
                <p class="name">{{ document.title }}</p>
                <p class="hint">
                  {{ labels[document.category] }} · {{ document.createdAt | shortDate: 'd MMM y' }}
                  @if (!document.available) { · <span class="pending">subida sin confirmar</span> }
                </p>
              </div>
              <div class="row-actions">
                @if (document.downloadUrl) {
                  <a class="ov-link" [href]="document.downloadUrl" target="_blank" rel="noopener">Abrir</a>
                }
                <button class="ov-link danger" type="button" (click)="remove(document)" [disabled]="busy()">Borrar</button>
              </div>
            </li>
          }
        </ul>
      }

      <form class="form" (ngSubmit)="upload()">
        <div class="ov-field">
          <label class="ov-eyebrow" [for]="id('title')">Nombre</label>
          <input class="ov-input" [id]="id('title')" name="title" [(ngModel)]="title" maxlength="120"
                 [placeholder]="unitId() ? 'Por ejemplo: Boleto de compraventa' : 'Por ejemplo: Reglamento de copropiedad'" />
        </div>
        <div class="ov-field">
          <label class="ov-eyebrow" [for]="id('category')">Tipo</label>
          <select class="ov-input" [id]="id('category')" name="category" [(ngModel)]="category">
            @for (option of categories; track option) {
              <option [value]="option">{{ labels[option] }}</option>
            }
          </select>
        </div>
        <label class="ov-btn ov-btn--secondary file">
          {{ file()?.name ?? 'Elegir archivo' }}
          <input type="file" accept="application/pdf,image/jpeg,image/png" (change)="pick($event)" [disabled]="busy()" />
        </label>
        <button class="ov-btn ov-btn--primary" type="submit" [disabled]="busy() || !file() || !title.trim()">
          {{ busy() ? 'Subiendo…' : 'Subir documento' }}
        </button>
      </form>
      <p class="hint">PDF, JPG o PNG. Sólo lo ven los compradores: no aparece en los enlaces que se comparten.</p>
      @if (error(); as message) {
        <p class="ov-error" role="alert">{{ message }}</p>
      }
    </div>
  `,
  styles: `
    .documents {
      display: grid;
      gap: var(--ov-space-3);
    }

    .hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .pending {
      color: var(--ov-status-delayed);
    }

    .list {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      padding: var(--ov-space-3) 0;
      border-bottom: 1px solid var(--ov-line);
    }

    .name {
      font-weight: var(--ov-weight-medium);
    }

    .row-actions {
      display: flex;
      gap: var(--ov-space-4);
    }

    .danger {
      color: var(--ov-danger);
    }

    .form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr));
      gap: var(--ov-space-3);
      align-items: end;
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
  `,
})
export class DocumentsManagerComponent implements OnInit {

  readonly developmentId = input.required<string>();
  /** Nulo: documentos de toda la obra. */
  readonly unitId = input<string | null>(null);

  private readonly documentsService = inject(DocumentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly labels = DOCUMENT_CATEGORY_LABELS;
  protected readonly categories = DOCUMENT_CATEGORIES;
  private readonly all = signal<UnitDocument[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly file = signal<File | null>(null);

  protected title = '';
  protected category: DocumentCategory = 'CONTRATO';

  protected readonly documents = computed(() => this.all().filter(document => document.unitId === this.unitId()));

  ngOnInit(): void {
    if (this.unitId() === null) {
      this.category = 'REGLAMENTO';
    }
    this.reload();
  }

  protected id(name: string): string {
    return `documents-${this.unitId() ?? 'obra'}-${name}`;
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const picked = input.files?.[0] ?? null;
    input.value = '';
    this.error.set(null);
    if (picked && !ACCEPTED_TYPES.includes(picked.type)) {
      this.error.set('El documento tiene que ser PDF, JPG o PNG.');
      this.file.set(null);
      return;
    }
    if (picked && picked.size > MAX_DOCUMENT_BYTES) {
      this.error.set('El documento pesa más de 25 MB.');
      this.file.set(null);
      return;
    }
    this.file.set(picked);
    if (picked && !this.title.trim()) {
      this.title = picked.name.replace(/\.[^.]+$/, '');
    }
  }

  protected upload(): void {
    const file = this.file();
    if (!file || !this.title.trim()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.documentsService.upload(this.developmentId(), { title: this.title.trim(), category: this.category, unitId: this.unitId() }, file)
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.title = '';
          this.file.set(null);
          this.reload();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.error.set(errorMessageOf(error, 'No se pudo subir el documento.'));
          this.reload();
        },
      });
  }

  protected remove(document: UnitDocument): void {
    if (!window.confirm(`¿Borrar "${document.title}"? El comprador deja de verlo.`)) {
      return;
    }
    this.busy.set(true);
    this.documentsService.remove(document.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.all.update(list => list.filter(item => item.id !== document.id));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo borrar el documento.'));
      },
    });
  }

  private reload(): void {
    this.documentsService.listForDevelopment(this.developmentId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: documents => this.all.set(documents),
        error: (error: unknown) => this.error.set(errorMessageOf(error, 'No se pudieron cargar los documentos.')),
      });
  }
}
