import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PublicFront } from '../../core/api/api.models';
import { aDevelopment, aUnit } from '../../testing/fixtures';
import { HouseFrontComponent } from './house-front.component';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const front = (overrides: Partial<PublicFront> = {}): PublicFront => ({
  sector: 'Frente', latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null, ...overrides,
});

describe('Frente de la casa, en Ficha y galería', () => {
  let fixture: ComponentFixture<HouseFrontComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(value: PublicFront): void {
    TestBed.configureTestingModule({
      imports: [HouseFrontComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true) } },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), unit: computed(() => aUnit()), reload: () => {} } },
      ],
    });
    fixture = TestBed.createComponent(HouseFrontComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-units/u1').flush({ unitId: 'u1', property: { front: value }, preview: { developmentId: 'd1' } });
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const slots = () => Array.from(element.querySelectorAll('.slot'));

  afterEach(() => http.verify());

  it('sin render ni fotos: "Subir render" y la explicación de quién sube la foto de hoy', () => {
    render(front());

    expect(text(slots()[0])).toContain('Render del frente');
    expect(text(slots()[0])).toContain('Subir render');
    expect(text(slots()[1])).toContain('Todavía no hay fotos del frente. Las sube el encargado desde Recorrido de hoy, en el lugar Frente.');
    expect(element.querySelector('app-compare-slider')).toBeNull();
  });

  it('sin lugar marcado como frente, lo dice y lleva a Cambiar sectores', () => {
    render(front({ sector: null }));

    expect(text(slots()[1])).toContain('Esta casa no tiene un lugar marcado como frente.');
    expect(slots()[1].querySelector('a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/recorridos?sectores=editar');
  });

  it('con render y foto de hoy: miniaturas, el estado en una línea, "Cambiar" y la vista del comprador plegada', () => {
    render(front({ renderUrl: 'https://s/render.jpg', latest: { url: 'https://s/hoy.jpg', thumbUrl: null, screenUrl: null, date: '2026-10-20' } }));

    expect(slots()[0].querySelector('img.thumb')?.getAttribute('src')).toBe('https://s/render.jpg');
    expect(text(slots()[0])).toContain('Cambiar');
    expect(slots()[1].querySelector('img.thumb')?.getAttribute('src')).toBe('https://s/hoy.jpg');
    expect(text(slots()[1].querySelector('.status')!)).toBe('Frente · 20 de octubre de 2026');
    const preview = element.querySelector<HTMLDetailsElement>('details.preview')!;
    expect(preview.open).toBe(false);
    expect(text(preview.querySelector('summary')!)).toBe('Ver cómo lo ve el comprador');
    const slider = preview.querySelector('app-compare-slider')!;
    expect(slider.querySelector('img.before')?.getAttribute('src')).toBe('https://s/render.jpg');
    expect(slider.querySelector('img.after')?.getAttribute('src')).toBe('https://s/hoy.jpg');
  });

  it('subir el render lo manda al render del frente, no a la galería, y vuelve a leer el frente', () => {
    render(front());
    const input = slots()[0].querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'render.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));

    const upload = http.expectOne('/api/units/u1/front-render/upload-url');
    expect(upload.request.body).toEqual({ filename: 'render.jpg', contentType: 'image/jpeg' });
    upload.flush({ uploadUrl: 'http://minio/put', contentType: 'image/jpeg' });
    http.expectOne('http://minio/put').flush(null);
    http.expectOne('/api/units/u1/front-render/confirm').flush(aUnit());
    http.expectOne('/api/my-units/u1').flush({
      unitId: 'u1', property: { front: front({ renderUrl: 'https://s/render.jpg' }) }, preview: { developmentId: 'd1' },
    });
    fixture.detectChanges();

    expect(slots()[0].querySelector('img')?.getAttribute('src')).toBe('https://s/render.jpg');
    http.expectNone(request => request.url.includes('/media/'));
  });

  it('la portada va arriba, con para qué sirve y su acción', () => {
    render(front());
    const cover = element.querySelector('.row.cover')!;

    expect(text(element.querySelector('h3')!)).toBe('Imágenes principales');
    expect(text(cover)).toContain('Portada');
    expect(text(cover)).toContain('Se usa si no hay fotos del frente.');
    expect(text(cover)).toMatch(/Subir portada|Cambiar/);
  });
});
