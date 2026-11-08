import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { MediaAsset, ProgressEntry } from '../../core/api/api.models';
import { aDevelopment, aMedia, aStage } from '../../testing/fixtures';
import { HouseStore } from './house.store';
import { WorkProgressComponent } from './work-progress.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function entry(id: string, entryDate: string): ProgressEntry {
  return {
    id, developmentId: 'd1', unitId: 'u1', stageId: 's1', entryDate, description: 'Avance ' + id, source: 'MANUAL',
    version: 1, rootId: id, current: true, createdAt: entryDate + 'T12:00:00Z',
  } as ProgressEntry;
}

describe('Historia de una casa', () => {
  let fixture: ComponentFixture<WorkProgressComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function flushLoad(entries: ProgressEntry[], media: MediaAsset[] = [], sectors: string[] = []): void {
    http.expectOne('/api/developments/d1/progress').flush(entries);
    http.expectOne('/api/units/u1/stages').flush([aStage({ id: 's1', unitId: 'u1', name: 'Mampostería' })]);
    http.expectOne('/api/developments/d1/media').flush(media);
    http.expectOne('/api/units/u1/sectors').flush(sectors);
    fixture.detectChanges();
  }

  function render(entries: ProgressEntry[], media: MediaAsset[] = [], sectors: string[] = [], query: Record<string, string> = {}): void {
    TestBed.configureTestingModule({
      imports: [WorkProgressComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true), reload: () => {} },
        },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), reload: () => {} } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
      ],
    });
    fixture = TestBed.createComponent(WorkProgressComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/audio-extractions').flush([]);
    flushLoad(entries, media, sectors);
    element = fixture.nativeElement as HTMLElement;
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label);

  afterEach(() => http.verify());

  it('el encabezado dice qué es y cuánto hay; publicar y el audio van al costado; los recorridos no se piden', () => {
    render([entry('e1', '2026-09-05')]);
    const header = element.querySelector('.intro')!;
    const aside = element.querySelector('app-history-aside')!;

    expect(text(header)).toContain('Historia de la casa');
    expect(text(header)).toContain('1 avance');
    expect(aside.querySelector('button')?.textContent?.trim()).toBe('Publicar avance');
    expect(text(aside)).toContain('Contarlo con un audio');
    expect(aside.querySelector('a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/historia/audios');
    expect(text(element)).not.toContain('Grabar recorrido 3D');
    expect(text(element)).not.toContain('Recorrido 3D');
    http.expectNone('/api/developments/d1/captures');
  });

  it('el formulario arranca cerrado, "Publicar avance" lo abre y "Cancelar" lo cierra', () => {
    render([entry('e1', '2026-09-05')]);
    expect(element.querySelector('#entry-form')).toBeNull();

    button('Publicar avance')!.click();
    fixture.detectChanges();
    const form = element.querySelector('#entry-form')!;
    expect(text(form.querySelector('h2')!)).toBe('Publicar un avance');
    expect(form.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);

    button('Cancelar')!.click();
    fixture.detectChanges();
    expect(element.querySelector('#entry-form')).toBeNull();
  });

  it('al publicar, el formulario se cierra y la lista se recarga', () => {
    render([]);
    button('Publicar avance')!.click();
    fixture.detectChanges();
    const description = element.querySelector<HTMLTextAreaElement>('#entry-description')!;
    description.value = 'Se terminó la losa';
    description.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    element.querySelector<HTMLButtonElement>('#entry-form button[type="submit"]')!.click();
    const create = http.expectOne(request => request.method === 'POST' && request.url === '/api/developments/d1/progress');
    expect(create.request.body).toEqual({ entryDate: expect.any(String), stageId: null, description: 'Se terminó la losa', unitId: 'u1' });
    create.flush(entry('e9', '2026-10-08'));
    flushLoad([entry('e9', '2026-10-08')]);

    expect(element.querySelector('#entry-form')).toBeNull();
    expect(element.querySelectorAll('.entry')).toHaveLength(1);
  });

  it('lista sólo avances por mes, con sus acciones; "Corregir" abre el formulario con el avance', () => {
    render([entry('e1', '2026-08-10'), entry('e2', '2026-09-05')]);

    expect(Array.from(element.querySelectorAll('.month-title')).map(text)).toEqual(['Septiembre 2026 · 1 avance', 'Agosto 2026 · 1 avance']);
    const first = element.querySelector('.entry')!;
    expect(text(first)).toContain('Mampostería');
    expect(text(first)).toContain('Avance e2');
    expect(text(first)).toContain('Corregir');
    expect(text(first)).toContain('Agregar fotos');

    button('Corregir')!.click();
    fixture.detectChanges();
    expect(text(element.querySelector('#entry-form h2')!)).toBe('Corregir avance');
    expect(element.querySelector<HTMLTextAreaElement>('#entry-description')!.value).toBe('Avance e2');
  });

  it('sin avances lo dice y ofrece publicar el primero', () => {
    render([]);
    const empty = element.querySelector('app-empty-state')!;

    expect(text(empty)).toContain('Todavía no hay avances publicados');
    Array.from(empty.querySelectorAll('button')).find(item => text(item) === 'Publicar avance')!.click();
    fixture.detectChanges();
    expect(element.querySelector('#entry-form')).not.toBeNull();
  });

  it('cada foto publicada lleva su lugar, y cambiarlo lo guarda en la foto', () => {
    const photo = aMedia({ id: 'm1', progressEntryId: 'e1', progressRootId: 'e1', unitId: 'u1', sector: 'Cocina' });
    render([entry('e1', '2026-09-05')], [photo], ['Frente', 'Cocina']);

    const chip = element.querySelector<HTMLSelectElement>('.entry app-sector-chip select')!;
    expect(chip.value).toBe('Cocina');
    chip.value = 'Frente';
    chip.dispatchEvent(new Event('change'));

    const patch = http.expectOne(request => request.method === 'PATCH' && request.url === '/api/media/m1/sector');
    expect(patch.request.body).toEqual({ sector: 'Frente' });
    patch.flush({ ...photo, sector: 'Frente' });
    fixture.detectChanges();
    expect(element.querySelector<HTMLSelectElement>('.entry app-sector-chip select')!.value).toBe('Frente');
  });

  it('en el formulario, cada foto elegida puede llevar un lugar, que viaja al pedir la subida', () => {
    render([], [], ['Frente', 'Cocina']);
    button('Publicar avance')!.click();
    fixture.detectChanges();

    const input = element.querySelector<HTMLInputElement>('#entry-form input[type="file"]')!;
    const file = new File(['x'], 'losa.jpg', { type: 'image/jpeg' });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const chip = element.querySelector<HTMLSelectElement>('.picked app-sector-chip select')!;
    expect(text(element.querySelector('.picked')!)).toContain('losa.jpg');
    chip.value = 'Cocina';
    chip.dispatchEvent(new Event('change'));
    const description = element.querySelector<HTMLTextAreaElement>('#entry-description')!;
    description.value = 'Se colocó la mesada';
    description.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    element.querySelector<HTMLButtonElement>('#entry-form button[type="submit"]')!.click();
    http.expectOne(request => request.method === 'POST' && request.url === '/api/developments/d1/progress')
      .flush(entry('e9', '2026-10-08'));
    const upload = http.expectOne('/api/developments/d1/media/upload-url');
    expect(upload.request.body).toEqual({ filename: 'losa.jpg', contentType: 'image/jpeg', progressEntryId: 'e9', sector: 'Cocina' });
    upload.flush({ mediaId: 'm9', uploadUrl: 'http://minio/put', contentType: 'image/jpeg' });
    http.expectOne('http://minio/put').flush(null);
    http.expectOne('/api/media/m9/confirm').flush(aMedia({ id: 'm9', sector: 'Cocina' }));
    flushLoad([entry('e9', '2026-10-08')], [], ['Frente', 'Cocina']);
  });

  /** Las fotos de un avance publicado, como las trae la galería. */
  const photosOf = (count: number) => Array.from({ length: count }, (_, i) => aMedia({
    id: 'p' + i, progressEntryId: 'e1', progressRootId: 'e1', unitId: 'u1', createdAt: `2026-09-05T10:00:${String(i).padStart(2, '0')}Z`,
  }));

  it('"Agregar fotos" con 10 fotos en el avance se deshabilita y dice que llegó al máximo', () => {
    render([entry('e1', '2026-09-05')], photosOf(10));

    const add = element.querySelector('.entry .file-link')!;
    expect(text(add)).toBe('Llegaste al máximo de 10 fotos');
    expect(add.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
  });

  it('"Agregar fotos" con 9: de una tanda de 3 sube la que entra y avisa cuántas quedaron afuera', () => {
    render([entry('e1', '2026-09-05')], photosOf(9));
    const add = element.querySelector('.entry .file-link')!;
    expect(text(add)).toBe('Agregar fotos');

    const input = add.querySelector<HTMLInputElement>('input')!;
    const files = ['a.jpg', 'b.jpg', 'c.jpg'].map(name => new File(['x'], name, { type: 'image/jpeg' }));
    Object.defineProperty(input, 'files', { value: files });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const upload = http.expectOne('/api/developments/d1/media/upload-url');
    expect(upload.request.body).toEqual(expect.objectContaining({ filename: 'a.jpg', progressEntryId: 'e1' }));
    upload.flush({ mediaId: 'p9', uploadUrl: 'http://minio/put', contentType: 'image/jpeg' });
    http.expectOne('http://minio/put').flush(null);
    http.expectOne('/api/media/p9/confirm').flush(aMedia({ id: 'p9' }));
    http.expectOne('/api/developments/d1/media').flush(photosOf(10));
    fixture.detectChanges();

    expect(Array.from(element.querySelectorAll('.ov-error')).map(text))
      .toContain('Un avance lleva hasta 10 fotos: quedaron afuera 2.');
    expect(text(element.querySelector('.entry .file-link')!)).toBe('Llegaste al máximo de 10 fotos');
  });

  it('sin sectores en la casa, las fotos no muestran el chip', () => {
    const photo = aMedia({ id: 'm1', progressEntryId: 'e1', progressRootId: 'e1', unitId: 'u1' });
    render([entry('e1', '2026-09-05')], [photo], []);

    expect(element.querySelector('.entry .photo img')).not.toBeNull();
    expect(element.querySelector('app-sector-chip')).toBeNull();
  });

  it('"Actualizar avance" desde Etapas abre el formulario con la etapa y su bloque, y manda todo junto', () => {
    render([], [], [], { etapa: 's1' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    expect(element.querySelector<HTMLSelectElement>('#entry-stage')?.value).toBe('s1');
    expect(element.querySelector<HTMLInputElement>('app-stage-update [role=switch]')?.checked).toBe(true);
    expect(element.querySelector<HTMLInputElement>('#entry-percent')?.value).toBe('40');
    element.querySelector<HTMLButtonElement>('[aria-label="Subir 5%"]')!.click();
    const description = element.querySelector<HTMLTextAreaElement>('#entry-description')!;
    description.value = 'Muros de planta alta terminados.';
    description.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element.querySelector<HTMLFormElement>('#entry-form form')!.dispatchEvent(new Event('submit'));

    const request = http.expectOne({ method: 'POST', url: '/api/developments/d1/progress' });
    expect(request.request.body.stageUpdate).toEqual({ progressPercent: 45, status: 'IN_PROGRESS' });
    request.flush(entry('e9', '2026-10-20'));
    expect(navigate).toHaveBeenCalledWith(['/obras', 'd1', 'casas', 'u1', 'etapas']);
  });

  it('un avance publicado sin prender el bloque no cambia la etapa', () => {
    render([]);
    button('Publicar avance')!.click();
    fixture.detectChanges();
    const stage = element.querySelector<HTMLSelectElement>('#entry-stage')!;
    stage.value = 's1';
    stage.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(element.querySelector<HTMLInputElement>('app-stage-update [role=switch]')?.checked).toBe(false);

    const description = element.querySelector<HTMLTextAreaElement>('#entry-description')!;
    description.value = 'Limpieza de obra.';
    description.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element.querySelector<HTMLFormElement>('#entry-form form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne({ method: 'POST', url: '/api/developments/d1/progress' });
    expect(request.request.body.stageUpdate).toBeUndefined();
    request.flush(entry('e9', '2026-10-20'));
    flushLoad([]);
  });

  it('cada avance dice quién lo publicó y qué le hizo a la etapa', () => {
    render([{ ...entry('e1', '2026-09-05'), source: 'AUDIO', authorName: 'Carlos Medina',
      stageChange: { percentBefore: 35, percentAfter: 50, statusBefore: 'IN_PROGRESS', statusAfter: 'IN_PROGRESS' } }]);
    const row = element.querySelector('app-history-entry')!;

    expect(text(row.querySelector('.stage')!)).toBe('Mampostería');
    expect(text(row.querySelector('.change')!)).toBe('35% → 50%');
    expect(text(row)).toContain('por audio');
    expect(text(row.querySelector('.foot')!)).toContain('Carlos Medina');
  });

  it('la carga inicial lleva su etiqueta, en gris: no es una alerta', () => {
    render([{ ...entry('e1', '2025-03-07'), source: 'INITIAL_LOAD' }]);
    const row = element.querySelector('app-history-entry')!;

    expect(text(row.querySelector('.initial')!)).toBe('Carga inicial');
    expect(text(row)).not.toContain('por audio');
  });

  it('filtra por etapa con cuántos avances tiene cada una; General es lo que no tiene etapa', () => {
    render([entry('e1', '2026-09-05'), { ...entry('e2', '2026-09-06'), stageId: null }]);
    const options = () => Array.from(element.querySelectorAll('app-history-aside [role=radio]')).map(item => item.getAttribute('aria-label'));

    expect(options()).toEqual(['Todas, 2 avances', 'Mampostería, 1 avance', 'General de la casa, 1 avance']);
    Array.from(element.querySelectorAll<HTMLButtonElement>('app-history-aside [role=radio]'))[2].click();
    fixture.detectChanges();
    expect(Array.from(element.querySelectorAll('app-history-entry .description')).map(text)).toEqual(['Avance e2']);
  });

  it('avisa cuántas fotos no tienen lugar, y "Asignar lugares" deja sólo los avances que las tienen', () => {
    render([entry('e1', '2026-09-05'), entry('e2', '2026-09-06')],
      [aMedia({ id: 'm1', progressEntryId: 'e1', progressRootId: 'e1', unitId: 'u1', sector: null }),
       aMedia({ id: 'm2', progressEntryId: 'e2', progressRootId: 'e2', unitId: 'u1', sector: 'Cocina' })],
      ['Cocina', 'Baño']);
    const aside = element.querySelector('app-history-aside')!;

    expect(text(aside.querySelector('.unplaced')!)).toContain('1 foto sin lugar');
    expect(text(aside.querySelector('.unplaced')!)).toContain('El comprador las ve en General.');
    expect(Array.from(element.querySelectorAll('app-history-entry .place')).map(text)).toEqual(['Cocina', 'Sin lugar']);
    Array.from(aside.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === 'Asignar lugares')!.click();
    fixture.detectChanges();
    expect(Array.from(element.querySelectorAll('app-history-entry .description')).map(text)).toEqual(['Avance e1']);
  });
});
