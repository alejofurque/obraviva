import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MediaAsset, ProjectComparison } from '../../core/api/api.models';
import { aDevelopment, aMedia, aUnit } from '../../testing/fixtures';
import { HouseSheetComponent } from './house-sheet.component';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function comparison(overrides: Partial<ProjectComparison>): ProjectComparison {
  return {
    id: 'c1', developmentId: 'd1', unitId: 'u1', title: 'Fachada', renderUrl: 'https://s/r.jpg', renderThumbUrl: null, renderScreenUrl: null, photoMediaId: 'm9',
    photoUrl: 'https://s/f.jpg', complete: true, createdAt: '2026-09-01T10:00:00Z', ...overrides,
  };
}

describe('Ficha y galería de una casa', () => {
  let fixture: ComponentFixture<HouseSheetComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(media: MediaAsset[], sectors: string[], comparisons: ProjectComparison[] = []): void {
    TestBed.configureTestingModule({
      imports: [HouseSheetComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true), reload: () => {} },
        },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), unit: computed(() => aUnit()), reload: () => {} } },
      ],
    });
    fixture = TestBed.createComponent(HouseSheetComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/units/u1/sectors').flush(sectors);
    http.expectOne('/api/developments/d1/media').flush(media);
    http.expectOne('/api/developments/d1/comparisons').flush(comparisons);
    // "Imágenes principales" lee el frente de la vista del comprador; acá no importa.
    http.expectOne('/api/my-units/u1').flush({
      unitId: 'u1', preview: { developmentId: 'd1' },
      property: { front: { sector: null, latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null } },
    });
    // Los documentos del comprador los pide su propio bloque; acá no importan.
    http.match(request => request.url.includes('/documents')).forEach(request => request.flush([]));
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label);

  it('salir con la ficha a medio editar avisa; sin tocar nada, no', () => {
    render([], []);
    expect(fixture.componentInstance.leaveWarning()).toBeNull();

    const code = element.querySelector<HTMLInputElement>('#house-code')!;
    code.value = 'Casa 7B';
    code.dispatchEvent(new Event('input'));

    expect(fixture.componentInstance.leaveWarning()).toBe('Tenés cambios sin guardar. ¿Salir igual?');
  });

  it('la galería es de venta: lo dice, no lleva lugar y las imágenes nuevas suben sin lugar', () => {
    render([], ['Frente', 'Cocina']);
    const gallery = element.querySelector('app-sales-gallery')!;

    expect(text(gallery)).toContain('Renders e imágenes del modelo terminado. No son fotos de la obra');
    expect(gallery.querySelector('app-sector-chip')).toBeNull();
    const input = gallery.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'render.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));

    const upload = http.expectOne('/api/developments/d1/media/upload-url');
    expect(upload.request.body).toEqual({
      filename: 'render.jpg', contentType: 'image/jpeg', unitId: 'u1', showcase: true, sector: null,
    });
  });

  it('"Mover a fotos de obra" pide el lugar y el día, la manda a la obra y la saca de la galería', () => {
    const photo = aMedia({ id: 'g1', unitId: 'u1', progressEntryId: null, progressRootId: null, showcase: true });
    render([photo], ['Frente', 'Contrafrente']);

    button('Mover a fotos de obra')!.click();
    fixture.detectChanges();
    const submit = element.querySelector<HTMLButtonElement>('.move-form button[type="submit"]')!;
    expect(submit.disabled).toBe(true);

    const sector = element.querySelector<HTMLSelectElement>('#move-sector')!;
    sector.value = 'Contrafrente';
    sector.dispatchEvent(new Event('change'));
    const date = element.querySelector<HTMLInputElement>('#move-date')!;
    date.value = '2026-10-04';
    date.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    element.querySelector<HTMLFormElement>('.move-form')!.dispatchEvent(new Event('submit'));

    const move = http.expectOne(request => request.method === 'POST' && request.url === '/api/media/g1/to-work');
    expect(move.request.body).toEqual({ sector: 'Contrafrente', takenOn: '2026-10-04' });
    move.flush({ ...photo, showcase: false, sector: 'Contrafrente', takenOn: '2026-10-04' });
    fixture.detectChanges();
    expect(element.querySelector('app-sales-gallery img')).toBeNull();
  });

  it('sin lugares en la casa, mover a la obra lleva a definirlos', () => {
    render([aMedia({ id: 'g1', unitId: 'u1', progressEntryId: null, progressRootId: null, showcase: true })], []);

    button('Mover a fotos de obra')!.click();
    fixture.detectChanges();
    expect(text(element.querySelector('.move-form')!)).toContain('Esta casa todavía no tiene lugares.');
    expect(element.querySelector('.move-form a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/recorridos');
  });

  it('al costado: imágenes principales y proyecto y realidad, que dice qué le falta a cada una', () => {
    render([], [], [comparison({}), comparison({ id: 'c2', title: 'Living', photoUrl: null, photoMediaId: null, complete: false }),
      comparison({ id: 'c3', unitId: 'otra' })]);
    const side = element.querySelector('.side')!;

    expect(text(side.querySelector('app-house-front h3')!)).toBe('Imágenes principales');
    const tiles = side.querySelectorAll('app-comparisons-summary li');
    expect(tiles).toHaveLength(2);
    expect(text(tiles[1])).toContain('Falta la foto: el comprador todavía no la ve.');
    expect(side.querySelector('app-comparisons-summary .head a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/historia/comparaciones');
  });
});
