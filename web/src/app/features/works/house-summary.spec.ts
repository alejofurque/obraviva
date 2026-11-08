import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PublicPlace, SetupStep, Stage, UnitOverview } from '../../core/api/api.models';
import { aDevelopment, aPlaceMonth, aPublicMedia, aStage, aUnit, anActivityItem } from '../../testing/fixtures';
import { HouseSummaryComponent } from './house-summary.component';
import { HouseStore } from './house.store';
import { countLabel } from './work-labels';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function step(key: SetupStep['key'], done: boolean): SetupStep {
  return { key, title: 'Paso ' + key, description: 'Hacer ' + key, done };
}

// Desordenadas a propósito: el Resumen las ordena por su número de etapa.
const STAGES: Stage[] = [
  aStage({ id: 's4', unitId: 'u1', name: 'Techo', orderIndex: 4, status: 'PENDING', progressPercent: 0 }),
  aStage({ id: 's3', unitId: 'u1', name: 'Instalaciones', orderIndex: 3, status: 'DELAYED', progressPercent: 20, startedOn: '2026-09-02' }),
  aStage({ id: 's1', unitId: 'u1', name: 'Fundaciones', orderIndex: 1, status: 'COMPLETED', progressPercent: 100 }),
  aStage({ id: 's2', unitId: 'u1', name: 'Mampostería', orderIndex: 2, status: 'IN_PROGRESS', progressPercent: 60,
    startedOn: '2026-10-08', plannedEnd: '2099-12-15' }),
];

const PLACES: PublicPlace[] = [
  { name: 'Living', months: [aPlaceMonth('2026-10', { tour: true, photos: [aPublicMedia('https://s/living.jpg', { entryDate: '2026-10-20' })] })] },
  { name: 'Baño', months: [aPlaceMonth('2026-10', { tour: true })] },
];

interface Scenario {
  role: 'OWNER' | 'MEMBER';
  estimatedDelivery?: string | null;
  setup?: SetupStep[] | null;
  stages?: Stage[];
}

let http: HttpTestingController;

function render({ role, estimatedDelivery = '2027-11', setup = null, stages = STAGES }: Scenario): HTMLElement {
  const unit = aUnit({ estimatedDelivery, progressPercent: 40 });
  const overview: UnitOverview = {
    unitId: 'u1', developmentId: 'd1', unit,
    content: {
      stages: 3, progressEntries: 4, photos: 1, galleryPhotos: 0, capturesAvailable: 1, capturesInProcess: 0,
      capturesFailed: 0, audiosToReview: 2, lastEntryDate: null, lastCaptureDate: null,
    },
    economic: role === 'OWNER' ? { totalBudget: 1_000_000, totalSpent: 300_000, budgetUsedPercent: 30, stagesWithBudget: 4 } : null,
    setup,
  };
  TestBed.configureTestingModule({
    imports: [HouseSummaryComponent],
    providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: LOCALE_ID, useValue: 'es-AR' },
      {
        provide: WorkStore,
        useValue: { development: signal(aDevelopment({ role })).asReadonly(), isOwner: computed(() => role === 'OWNER') },
      },
      {
        provide: HouseStore,
        useValue: { overview: signal(overview).asReadonly(), unit: signal(unit).asReadonly(), unitId: computed(() => 'u1') },
      },
    ],
  });
  const fixture = TestBed.createComponent(HouseSummaryComponent);
  fixture.detectChanges();
  http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/units/u1/stages').flush(stages);
  // La actividad: las mismas novedades del desarrollo, filtradas a esta casa.
  const feed = http.expectOne(request => request.url === '/api/developments/d1/activity');
  expect(feed.request.params.get('unitId')).toBe('u1');
  expect(feed.request.params.get('limit')).toBe('6');
  feed.flush({ items: [anActivityItem({ id: 'entry:a' }), anActivityItem({ id: 'entry:b' })], nextCursor: null, seenUntil: null });
  // Los lugares salen de la misma vista del comprador.
  http.expectOne('/api/my-units/u1').flush({ unitId: 'u1', property: { places: PLACES }, preview: null });
  if (role === 'OWNER') {
    http.expectOne('/api/units/u1/members').flush([{ id: 'm1', unitId: 'u1', userId: 'b1', email: null, fullName: 'Lucía Paz', createdAt: '2026-01-01T00:00:00Z' }]);
    http.expectOne('/api/units/u1/share-links').flush([{
      id: 'l1', label: null, active: true, expiresAt: null, revokedAt: null, lastAccessedAt: null, accessCount: 0, createdAt: '2026-10-01T00:00:00Z',
    }]);
    http.expectOne(request => request.url === '/api/developments/d1/dashboard' && request.params.get('unitId') === 'u1')
      .flush({ alerts: [{ stageId: 's3', name: 'Instalaciones', unitId: 'u1', unitCode: 'Casa 1', trafficLight: 'RED', budgetUsedPercent: 70, progressPercent: 20 }] });
  }
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

afterEach(() => http?.verify());

describe('Resumen de una casa', () => {
  it('arriba: etapas, gasto con su lectura, entrega con la próxima etapa y el comprador', () => {
    const element = render({ role: 'OWNER' });

    expect(Array.from(element.querySelectorAll('.kpi .label')).map(text)).toEqual(['Etapas', 'Gasto', 'Entrega estimada', 'Comprador']);
    const kpis = Array.from(element.querySelectorAll('.kpi')).map(text);
    expect(kpis[0]).toContain('1 de 4 terminadas');
    expect(kpis[0]).toContain('2 en curso · 1 con demora');
    expect(kpis[1]).toContain('US$ 300 mil de US$ 1 M');
    expect(kpis[1]).toContain('1 etapa en alerta · 30% gastado, 40% de avance');
    expect(kpis[2]).toContain('Nov 2027');
    expect(kpis[2]).toContain('Próxima etapa: Techo');
    expect(kpis[3]).toContain('Lucía Paz');
    expect(kpis[3]).toContain('1 enlace activo');
  });

  it('las etapas en curso, compactas y por su número, con desde cuándo y el fin previsto', () => {
    const element = render({ role: 'OWNER' });

    const running = Array.from(element.querySelectorAll('.running'));
    expect(running.map(row => text(row.querySelector('.name')!))).toEqual(['Mampostería', 'Instalaciones']);
    expect(text(running[0].querySelector('.meta')!)).toBe('En curso desde el 8 oct · fin previsto dic');
    expect(text(running[1].querySelector('.meta')!)).toContain('Demorada desde el 2 sep');
    const all = Array.from(element.querySelectorAll('a')).find(link => text(link) === 'Todas las etapas');
    expect(all?.getAttribute('href')).toBe(`/obras/${aDevelopment().id}/casas/u1/etapas`);
  });

  it('los lugares con su miniatura y el 3D; la actividad de la casa con "Ver todo" a sus novedades', () => {
    const element = render({ role: 'OWNER' });

    const places = Array.from(element.querySelectorAll('.place'));
    expect(places.map(place => text(place.querySelector('.place-name')!))).toEqual(['Living', 'Baño']);
    expect(places[0].querySelector('img')?.getAttribute('src')).toBe('https://s/living.jpg');
    expect(places[1].querySelector('.thumb.tour-only')).not.toBeNull();
    expect(places.every(place => place.querySelector('.badge'))).toBe(true);
    expect(text(places[0].querySelector('.place-meta')!)).toBe('3D y fotos · 20 oct');

    expect(element.querySelectorAll('app-activity-line')).toHaveLength(2);
    const all = Array.from(element.querySelectorAll('a')).find(link => text(link) === 'Ver todo');
    expect(all?.getAttribute('href')).toBe(`/obras/${aDevelopment().id}/casas/u1/novedades`);
  });

  it('si faltan datos, una sola línea lleva a la pestaña donde se completan', () => {
    const element = render({ role: 'OWNER', setup: [step('STAGES', true), step('SHEET', false), step('FIRST_CAPTURE', false)] });

    const missing = element.querySelectorAll('.missing');
    expect(missing).toHaveLength(1);
    expect(text(missing[0])).toBe('Faltan algunos datos por completar · Ver');
  });

  it('sin datos faltantes no hay línea, aunque falte grabar o vincular comprador', () => {
    const element = render({ role: 'OWNER', setup: [step('SHEET', true), step('FIRST_CAPTURE', false), step('BUYER', false)] });
    expect(element.querySelector('.missing')).toBeNull();
  });

  it('al encargado no le muestra gasto, comprador ni datos faltantes; sin fecha, la entrega queda sin definir', () => {
    const element = render({ role: 'MEMBER', estimatedDelivery: null, stages: [STAGES[2], STAGES[1]] });

    expect(Array.from(element.querySelectorAll('.kpi .label')).map(text)).toEqual(['Etapas', 'Entrega estimada']);
    expect(text(element)).toContain('Sin definir');
    expect(element.querySelector('.missing')).toBeNull();
  });

  it('sin etapas en ejecución lo dice en un texto discreto', () => {
    const element = render({ role: 'OWNER', stages: [STAGES[0], STAGES[2]] });
    expect(text(element)).toContain('Ninguna etapa en ejecución.');
  });
});

describe('Plurales', () => {
  it('una foto, una etapa; varias, en plural', () => {
    expect(countLabel(1, 'foto seleccionada', 'fotos seleccionadas')).toBe('1 foto seleccionada');
    expect(countLabel(3, 'foto seleccionada', 'fotos seleccionadas')).toBe('3 fotos seleccionadas');
    expect(countLabel(1, 'etapa', 'etapas')).toBe('1 etapa');
  });
});
