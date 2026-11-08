import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PropertyViewStore } from './property-view.store';

describe('PropertyViewStore', () => {
  it('ante una imagen que falla renueva la vista vencida una sola vez por URL', () => {
    TestBed.configureTestingModule({
      providers: [PropertyViewStore, provideHttpClient(), provideHttpClientTesting()],
    });
    const store = TestBed.inject(PropertyViewStore);
    const http = TestBed.inject(HttpTestingController);
    store.load({ kind: 'public', token: 't' });
    http.expectOne('/api/public/share/t').flush({ unitCode: 'Casa 1' });
    // Como si la página llevara más de cinco minutos abierta.
    (store as unknown as { loadedAt: number }).loadedAt = 0;

    store.renewAfterFailure('https://s/vencida.jpg');
    http.expectOne('/api/public/share/t').flush({ unitCode: 'Casa 1' });
    (store as unknown as { loadedAt: number }).loadedAt = 0;
    store.renewAfterFailure('https://s/vencida.jpg');

    http.expectNone('/api/public/share/t');
    http.verify();
  });

  describe('"Ver como el comprador"', () => {
    function store(): { store: PropertyViewStore; http: HttpTestingController } {
      TestBed.configureTestingModule({
        providers: [PropertyViewStore, provideHttpClient(), provideHttpClientTesting()],
      });
      return { store: TestBed.inject(PropertyViewStore), http: TestBed.inject(HttpTestingController) };
    }

    it('la marca sale del backend: con preview, es la vista del dueño', () => {
      const { store: view, http } = store();
      view.load({ kind: 'private', unitId: 'u1' });
      http.expectOne('/api/my-units/u1').flush({ unitId: 'u1', property: { unitCode: 'Casa 1' }, preview: { developmentId: 'd1' } });

      expect(view.preview()).toEqual({ developmentId: 'd1' });
      expect(view.unitId()).toBe('u1');
    });

    it('un comprador real nunca tiene la marca, y el enlace público tampoco', () => {
      const { store: view, http } = store();
      view.load({ kind: 'private', unitId: 'u1' });
      http.expectOne('/api/my-units/u1').flush({ unitId: 'u1', property: { unitCode: 'Casa 1' }, preview: null });
      expect(view.preview()).toBeNull();

      view.load({ kind: 'public', token: 't' });
      http.expectOne('/api/public/share/t').flush({ unitCode: 'Casa 1' });
      expect(view.preview()).toBeNull();
      expect(view.unitId()).toBeNull();
    });
  });
});
