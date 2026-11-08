import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Params } from '@angular/router';
import { Observable, map, of, tap } from 'rxjs';
import { OwnerPreview, PublicUnitView } from '../../core/api/api.models';
import { MyPropertyService } from '../../core/api/my-property.service';
import { PublicShareService } from '../../core/api/public-share.service';
import { errorMessageOf } from '../../shared/http/error-message';

/** De dónde sale la vista: la sesión de quien compró, o el token de un enlace. */
export type PropertySource =
  | { kind: 'private'; unitId: string }
  | { kind: 'public'; token: string };

/**
 * Las URLs firmadas vencen: una hora en la sesión y cinco minutos en el enlace público.
 * Antes de usar un archivo, la vista se recarga si está cerca de ese límite.
 */
const REFRESH_AFTER_MS: Record<PropertySource['kind'], number> = {
  private: 50 * 60 * 1000,
  public: 4 * 60 * 1000,
};

/**
 * Estado compartido por las pestañas de una propiedad. Lo provee el marco de la
 * propiedad, así cada pestaña lee la misma vista sin volver a pedirla.
 */
@Injectable()
export class PropertyViewStore {

  private readonly myProperty = inject(MyPropertyService);
  private readonly publicShare = inject(PublicShareService);

  private readonly sourceSignal = signal<PropertySource | null>(null);
  private readonly viewSignal = signal<PublicUnitView | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly errorSignal = signal<{ message: string; retryable: boolean } | null>(null);
  private loadedAt = 0;

  readonly source = this.sourceSignal.asReadonly();
  readonly view = this.viewSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly isPublic = computed(() => this.sourceSignal()?.kind === 'public');
  /** La primera pestaña: "Mi casa" para quien la compró; "La casa" para quien abre un enlace. */
  readonly homeLabel = computed(() => (this.isPublic() ? 'La casa' : 'Mi casa'));

  private readonly navQuerySignal = signal<Params>({});
  /**
   * Lo que viaja entre pestañas: `obra` ("Ver como el comprador") y `desde` (el encargado
   * que vino de Hoy). Lo de una pantalla (`mes`, `sector`, `lugar`) no pasa a las otras.
   */
  readonly navQuery = this.navQuerySignal.asReadonly();

  keepQuery(params: Params): void {
    this.navQuerySignal.set(Object.fromEntries(Object.entries(params).filter(([, value]) => value != null && value !== '')));
  }

  private readonly previewSignal = signal<OwnerPreview | null>(null);
  /**
   * "Ver como el comprador": lo manda el backend sólo a quien llega por la organización.
   * Nunca sale del parámetro ?obra=, que cualquiera puede escribir.
   */
  readonly preview = this.previewSignal.asReadonly();
  /** La casa de la vista privada, para los enlaces de los avisos; nula en el enlace público. */
  readonly unitId = computed(() => {
    const source = this.sourceSignal();
    return source?.kind === 'private' ? source.unitId : null;
  });

  /** Base de los enlaces entre pestañas, según de dónde se abrió la propiedad. */
  readonly basePath = computed<string[]>(() => {
    const source = this.sourceSignal();
    if (!source) {
      return ['/'];
    }
    return source.kind === 'private' ? ['/mi-propiedad', source.unitId] : ['/p', source.token];
  });

  load(source: PropertySource): void {
    this.sourceSignal.set(source);
    this.fetch().subscribe();
  }

  reload(): void {
    this.fetch().subscribe();
  }

  /** URLs que ya fallaron una vez: se renueva la vista una sola vez por archivo. */
  private readonly failedUrls = new Set<string>();

  /**
   * Una imagen con carga diferida puede pedirse cuando su URL firmada ya venció (cinco
   * minutos en el enlace público). Se renueva la vista una vez por URL: las imágenes vuelven
   * con URLs nuevas y el navegador las carga solo.
   */
  renewAfterFailure(url: string): void {
    if (this.failedUrls.has(url)) {
      return;
    }
    this.failedUrls.add(url);
    this.ensureFresh().subscribe();
  }

  /** Devuelve la vista con URLs vigentes, recargándola si hace falta. */
  ensureFresh(): Observable<PublicUnitView | null> {
    const source = this.sourceSignal();
    const current = this.viewSignal();
    if (!source || (current && Date.now() - this.loadedAt < REFRESH_AFTER_MS[source.kind])) {
      return of(current);
    }
    return this.fetch();
  }

  private fetch(): Observable<PublicUnitView | null> {
    const source = this.sourceSignal();
    if (!source) {
      return of(null);
    }
    this.loadingSignal.set(true);
    this.errorSignal.set(null);

    const request = source.kind === 'private'
      ? this.myProperty.property(source.unitId).pipe(
        tap(response => this.previewSignal.set(response.preview ?? null)),
        map(response => response.property))
      : this.publicShare.view(source.token).pipe(tap(() => this.previewSignal.set(null)));

    return new Observable<PublicUnitView | null>(subscriber => {
      const subscription = request
        .pipe(tap(view => {
          this.viewSignal.set(view);
          this.loadedAt = Date.now();
        }))
        .subscribe({
          next: view => {
            this.loadingSignal.set(false);
            subscriber.next(view);
            subscriber.complete();
          },
          error: (error: unknown) => {
            this.loadingSignal.set(false);
            this.errorSignal.set(this.describe(error, source));
            subscriber.next(null);
            subscriber.complete();
          },
        });
      return () => subscription.unsubscribe();
    });
  }

  private describe(error: unknown, source: PropertySource): { message: string; retryable: boolean } {
    if (error instanceof HttpErrorResponse && error.status === 404) {
      // Revocado, vencido o inexistente se ven igual desde afuera, a propósito.
      return source.kind === 'public'
        ? { message: 'Este enlace ya no está disponible. Pedile uno nuevo a quien te lo compartió.', retryable: false }
        : { message: 'No encontramos esta propiedad en tu cuenta.', retryable: false };
    }
    return { message: errorMessageOf(error, 'No se pudo cargar la propiedad.'), retryable: true };
  }
}
