import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Development, MediaAsset, ProgressEntry, Stage } from '../../core/api/api.models';
import { isDiscardable, processingLabel } from './work-captures.component';
import { photosByEntry, selectPhotos } from './work-progress.component';
import { WorkStagesComponent, weightedProgress } from './work-stages.component';
import { WorkStore } from './work.store';
import { aDevelopment, aMedia, aStage, anEntry } from '../../testing/fixtures';
import { HouseStore } from './house.store';
import { galleryOf } from './sales-gallery.component';

registerLocaleData(localeEsAr);

const stage = (overrides: Partial<Stage> = {}): Stage => aStage(overrides);
const media = (overrides: Partial<MediaAsset> = {}): MediaAsset => aMedia(overrides);

function file(name: string, type: string, size = 1024): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe('Lógica de la carga diaria', () => {
  it('el avance general pondera igual que el servidor', () => {
    // (100×3 + 40×1) / 4 = 85
    expect(weightedProgress([stage({ progressPercent: 100, weight: 3 }), stage({ progressPercent: 40 })])).toBe(85);
    expect(weightedProgress([])).toBe(0);
  });

  it('agrupa las fotos por avance raíz, en orden de subida, y deja afuera videos y fotos sueltas', () => {
    const groups = photosByEntry([
      media({ id: 'tarde', createdAt: '2026-09-10T12:00:00Z' }),
      media({ id: 'temprano', createdAt: '2026-09-10T09:00:00Z', progressEntryId: 'e1-v2' }),
      media({ id: 'video', kind: 'VIDEO' }),
      media({ id: 'suelta', progressEntryId: null, progressRootId: null }),
    ]);

    expect(groups.get('e1')?.map(photo => photo.id)).toEqual(['temprano', 'tarde']);
    expect(groups.size).toBe(1);
  });

  it('acepta imágenes de hasta 15 MB y explica lo que queda afuera', () => {
    const { accepted, problems } = selectPhotos([
      file('losa.jpg', 'image/jpeg'),
      file('plano.pdf', 'application/pdf'),
      file('enorme.jpg', 'image/jpeg', 16 * 1024 * 1024),
    ]);

    expect(accepted.map(f => f.name)).toEqual(['losa.jpg']);
    expect(problems).toEqual(['plano.pdf no es una imagen.', 'enorme.jpg pesa más de 15 MB.']);
  });

  it('describe el procesamiento sin términos internos y sólo deja descartar lo que no avanza', () => {
    expect(processingLabel({ jobStatus: 'PROCESSING', pipelineStep: 'AWAIT_EXTERNAL' })).toContain('Reconstruyendo en 3D');
    expect(processingLabel({ jobStatus: 'FAILED', pipelineStep: 'PREPROCESS' })).toBe('No se pudo procesar');
    expect(isDiscardable({ jobStatus: 'FAILED' })).toBe(true);
    expect(isDiscardable({ jobStatus: 'UPLOADED' })).toBe(true);
    expect(isDiscardable({ jobStatus: 'PENDING' })).toBe(false);
  });
});

describe('WorkStagesComponent', () => {
  function render(role: 'OWNER' | 'MEMBER', house: string | null, stages: Stage[], entries: ProgressEntry[] = []) {
    const work: Development = aDevelopment({ role, overallProgressPercent: 40 });
    const providers: unknown[] = [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: LOCALE_ID, useValue: 'es-AR' },
      { provide: WorkStore, useValue: { development: signal(work).asReadonly(), isOwner: computed(() => role === 'OWNER'), reload: () => {} } },
    ];
    if (house) {
      providers.push({ provide: HouseStore, useValue: { unitId: signal(house).asReadonly(), reload: () => {} } });
    }
    TestBed.configureTestingModule({ imports: [WorkStagesComponent], providers: providers as never[] });
    const fixture = TestBed.createComponent(WorkStagesComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(house ? `/api/units/${house}/stages` : '/api/developments/d1/stages').flush(stages);
    http.expectOne('/api/developments/d1/progress').flush(entries);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const button = (label: string) => Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find(candidate => candidate.textContent?.trim() === label);
    return { element, http, button, detect: () => fixture.detectChanges() };
  }

  it('en las obras comunes el dueño agrega etapas y edita su detalle con fechas y peso', () => {
    const { element, button, detect } = render('OWNER', null, [stage()]);

    expect(element.textContent).toContain('Etapas de las obras comunes');
    expect(element.querySelector('form.create')).toBeNull();
    button('Agregar etapa')!.click();
    detect();
    expect(element.querySelector('form.create')).not.toBeNull();

    button('Editar')!.click();
    detect();
    expect(element.querySelector('app-stage-edit #weight-s1')).not.toBeNull();
    expect(element.querySelector('app-stage-edit #end-s1')).not.toBeNull();
  });

  it('el encargado corrige sin publicar el estado con la fecha del cambio, sin estructurar', () => {
    const { element, http, button, detect } = render('MEMBER', 'u1', [stage({ unitId: 'u1' })]);

    expect(button('Agregar etapa')).toBeUndefined();
    button('Editar')!.click();
    detect();
    expect(element.querySelector('#progress-s1')).not.toBeNull();
    expect(element.querySelector('#weight-s1')).toBeNull();

    const date = element.querySelector<HTMLInputElement>('#when-s1')!;
    date.value = '2026-09-01';
    date.dispatchEvent(new Event('change'));
    const select = element.querySelector<HTMLSelectElement>('#status-s1')!;
    expect(select.value).toBe('IN_PROGRESS');
    select.value = 'COMPLETED';
    select.dispatchEvent(new Event('change'));
    const request = http.expectOne('/api/developments/stages/s1');
    expect(request.request.body).toEqual({ status: 'COMPLETED', effectiveOn: '2026-09-01' });
  });

  it('por estado: en curso con su último avance y "Actualizar avance" a la Historia; lo que viene se empieza', () => {
    const { element, http, button } = render('MEMBER', 'u1', [
      stage({ id: 's1', unitId: 'u1', orderIndex: 1, status: 'COMPLETED', progressPercent: 100, completedOn: '2026-05-10' }),
      stage({ id: 's2', unitId: 'u1', orderIndex: 2, name: 'Instalaciones', status: 'DELAYED', progressPercent: 55, plannedEnd: '2026-01-10' }),
      stage({ id: 's3', unitId: 'u1', orderIndex: 3, name: 'Aberturas', status: 'PENDING', progressPercent: 0 }),
    ], [anEntry({ stageId: 's2', entryDate: '2026-10-14', description: 'Cañería de gas hasta la cocina.' })]);

    expect(element.querySelector('.summary')?.textContent).toContain('1 terminada · 1 en curso (1 con demora) · 1 por empezar');
    const card = element.querySelector('app-stage-board article')!;
    expect(card.querySelector('.chip')?.textContent?.trim()).toBe('Demorada');
    expect(card.querySelector('.overdue')?.textContent).toContain('pasó');
    expect(card.querySelector('.last')?.textContent).toContain('Cañería de gas hasta la cocina.');
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/historia?etapa=s2');
    expect(element.querySelector('app-stage-board .pill')?.textContent).toContain('Estructura');

    button('Empezar')!.click();
    const request = http.expectOne('/api/developments/stages/s3');
    expect(request.request.body).toEqual({ status: 'IN_PROGRESS', effectiveOn: null });
  });

  it('la lista muestra todas las etapas, con el fin previsto vencido en ámbar y sin rojo', () => {
    const { element, button, detect } = render('OWNER', 'u1', [
      stage({ id: 's1', unitId: 'u1', status: 'IN_PROGRESS', plannedEnd: '2026-01-10', weight: 8 }),
      stage({ id: 's2', unitId: 'u1', orderIndex: 2, status: 'PENDING', plannedStart: '2027-02-01' }),
    ]);

    button('Lista')!.click();
    detect();
    const rows = element.querySelectorAll('app-stage-table tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('.overdue')?.textContent).toContain('pasó');
    expect(rows[1].textContent).toContain('Prev.');
    expect(element.querySelector('[class*="danger"]')).toBeNull();
  });

  it('una casa sin etapas le ofrece al dueño cargar las etapas estándar', () => {
    const { button, http } = render('OWNER', 'u1', []);

    button('Cargar etapas estándar')!.click();
    expect(http.expectOne('/api/units/u1/stages/standard').request.method).toBe('POST');
  });
});

describe('galleryOf', () => {
  it('son las fotos de venta de la casa, de la más vieja a la más nueva; las de la sesión de recorrido no', () => {
    const photos = galleryOf([
      media({ id: 'avance', unitId: 'u1' }),
      media({ id: 'nueva', unitId: 'u1', progressEntryId: null, progressRootId: null, showcase: true, createdAt: '2026-09-12T10:00:00Z' }),
      media({ id: 'vieja', unitId: 'u1', progressEntryId: null, progressRootId: null, showcase: true, createdAt: '2026-09-01T10:00:00Z' }),
      media({ id: 'sesion', unitId: 'u1', progressEntryId: null, progressRootId: null, sector: 'Frente', takenOn: '2026-09-10' }),
      media({ id: 'otra-casa', unitId: 'u2', progressEntryId: null, progressRootId: null, showcase: true }),
      media({ id: 'video', unitId: 'u1', progressEntryId: null, progressRootId: null, kind: 'VIDEO' }),
    ], 'u1');

    expect(photos.map(photo => photo.id)).toEqual(['vieja', 'nueva']);
  });
});
