import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import {
  Capture3d,
  Development,
  DevelopmentOverview,
  HouseCard,
  ProgressEntry,
  SetupStep,
} from '../../core/api/api.models';
import { parseAmount } from './amounts';
import { developmentStepPath } from './work-labels';
import { aDevelopment, aStage, aUnit, anActivityItem } from '../../testing/fixtures';
import { entriesByMonth } from './work-progress.component';
import { WorkSummaryComponent, activityOf, missingDataPath } from './work-summary.component';
import { WorkAudioReviewComponent } from './work-audio-review.component';
import { previousMonth } from './work-reports.component';
import { WorkTeamComponent } from './work-team.component';
import { NO_ZONE, viewerZones } from './work-viewer.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ') ?? '';

function entry(id: string, entryDate: string, overrides: Partial<ProgressEntry> = {}): ProgressEntry {
  return {
    id, developmentId: 'd1', unitId: null, stageId: null, entryDate, description: 'Avance ' + id, source: 'MANUAL',
    version: 1, rootId: id, current: true, createdAt: entryDate + 'T12:00:00Z',
    ...overrides,
  } as ProgressEntry;
}

function capture(id: string, captureDate: string, zone: string | null = null): Capture3d {
  return {
    id, developmentId: 'd1', unitId: null, stageId: null, captureDate, description: null, zone, status: 'AVAILABLE',
    transform: { position: { x: 0, y: 0, z: 0 }, rotationDeg: { x: 0, y: 0, z: 0 }, scale: 1 }, splatUrl: 'https://s/' + id,
  };
}

function step(key: SetupStep['key'], done: boolean): SetupStep {
  return { key, title: 'Paso ' + key, description: 'Hacer ' + key, done };
}

describe('Historia de la obra', () => {
  it('agrupa los avances por mes, del más nuevo al más viejo, y en un mismo día el último publicado primero', () => {
    const months = entriesByMonth([
      entry('e1', '2026-08-10'),
      entry('e2', '2026-09-05', { createdAt: '2026-09-05T09:00:00Z' }),
      entry('e3', '2026-09-05', { createdAt: '2026-09-05T18:00:00Z' }),
      entry('e4', '2026-07-01'),
    ]);

    expect(months.map(group => group.month)).toEqual(['2026-09', '2026-08', '2026-07']);
    expect(months[0].entries.map(item => item.id)).toEqual(['e3', 'e2']);
  });

  it('los últimos momentos del resumen dejan afuera las versiones corregidas', () => {
    const items = activityOf(
      [entry('e1', '2026-08-10', { current: false }), entry('e2', '2026-08-10', { source: 'AUDIO' })],
      [capture('c1', '2026-09-01', 'Planta baja')],
      () => 'Mampostería',
    );

    expect(items.map(item => item.id)).toEqual(['c1', 'e2']);
    expect(items[0].title).toBe('Recorrido 3D · Planta baja');
    expect(items[1].audio).toBe(true);
  });
});

describe('Datos por completar', () => {
  it('lo del desarrollo se completa en sus pestañas y lo de cada casa, desde Casas', () => {
    expect(developmentStepPath('DETAILS')).toBe('datos');
    expect(developmentStepPath('TEAM')).toBe('equipo');
    expect(developmentStepPath('SHEETS')).toBe('casas');
    expect(developmentStepPath('FIRST_CAPTURE')).toBe('casas');
  });

  it('lleva a la pestaña del primer dato que falta, en el orden del servidor', () => {
    expect(missingDataPath([step('DETAILS', true), step('SHEETS', false), step('TEAM', false)])).toBe('casas');
    expect(missingDataPath([step('DETAILS', true), step('SHEETS', true), step('TEAM', false)])).toBe('equipo');
    expect(missingDataPath([step('DETAILS', false), step('TEAM', false)])).toBe('datos');
  });

  it('no cuenta como dato faltante lo que llega con la obra y la venta', () => {
    expect(missingDataPath([
      step('DETAILS', true), step('UNITS', true), step('STAGES', true), step('SHEETS', true), step('TEAM', true),
      step('FIRST_ENTRY', false), step('FIRST_CAPTURE', false), step('BUYER', false),
    ])).toBeNull();
    expect(missingDataPath(null)).toBeNull();
  });
});

describe('Económico', () => {
  it('lee montos como los escribe una persona y distingue vacío de cero', () => {
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('0')).toBe(0);
    expect(parseAmount('18.500.000')).toBe(18500000);
    expect(parseAmount('1250,50')).toBe(1250.5);
    expect(parseAmount('-3')).toBe('invalid');
    expect(parseAmount('mucho')).toBe('invalid');
  });
});

describe('WorkSummaryComponent por rol', () => {
  let http: HttpTestingController;

  function render(role: 'OWNER' | 'MEMBER', overview: DevelopmentOverview): HTMLElement {
    const work: Development = aDevelopment({
      role, name: 'Barrio Los Aromos', description: 'Barrio cerrado al pie de las sierras.', locality: 'Villa Allende',
      amenities: ['Pileta'], developmentType: 'BARRIO_CERRADO', overallProgressPercent: 22, estimatedDelivery: '2099-07',
    });
    TestBed.configureTestingModule({
      imports: [WorkSummaryComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: {
            development: signal(work).asReadonly(),
            isOwner: computed(() => role === 'OWNER'),
            isSingle: computed(() => false),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(WorkSummaryComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/overview').flush(overview);
    http.expectOne('/api/developments/d1/units').flush([
      aUnit({ id: 'u2', code: 'Casa 2', stageCount: 12, progressPercent: 45, salesStatus: 'SOLD' }),
      aUnit({ id: 'u1', code: 'Casa 1', stageCount: 12, progressPercent: 0 }),
    ]);
    if (role === 'OWNER') {
      http.expectOne('/api/developments/d1/activity/pending-audios').flush([{
        extractionId: 'x1', unitId: 'u2', unitCode: 'Casa 2', authorId: 'p1', authorName: 'Carlos Medina',
        createdAt: '2026-10-20T12:00:00Z', progressNotes: 2, stageChanges: 1,
      }]);
      http.expectOne(request => request.url === '/api/developments/d1/dashboard').flush({ alerts: [{
        stageId: 's1', name: 'Cubierta y techo', unitId: 'u2', unitCode: 'Casa 2', trafficLight: 'RED', budgetUsedPercent: 62, progressPercent: 30,
      }] });
      const feed = http.expectOne(request => request.url === '/api/developments/d1/activity');
      expect(feed.request.params.get('limit')).toBe('5');
      feed.flush({ items: [anActivityItem({ unseen: true })], nextCursor: null, seenUntil: null });
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  const content = {
    stages: 24, progressEntries: 4, photos: 3, galleryPhotos: 2, capturesAvailable: 2, capturesInProcess: 0, capturesFailed: 1,
    audiosToReview: 2, lastEntryDate: '2026-09-01', lastCaptureDate: '2026-09-03',
  };
  const houses: HouseCard[] = [
    {
      unitId: 'u2', currentStageName: 'Cubierta y techo', otherRunningStages: 2, plannedStart: null,
      lastActivity: anActivityItem({ unitId: 'u2' }), unseen: true, hasSupervisor: true, alertLight: 'RED', alertStageName: 'Cubierta y techo',
    },
    {
      unitId: 'u1', currentStageName: 'Trabajos preliminares', otherRunningStages: 0, plannedStart: '2026-11-03',
      lastActivity: null, unseen: false, hasSupervisor: false, alertLight: null, alertStageName: null,
    },
  ];

  it('al dueño: indicadores, casas con su etapa, lo último y la alerta, y al costado "Para revisar" y las novedades', () => {
    const element = render('OWNER', {
      developmentId: 'd1', kind: 'MULTI_UNIT', content, houses,
      economic: { totalBudget: 1_000_000, totalSpent: 250_000, budgetUsedPercent: 25, stagesWithBudget: 6 },
      commercial: { units: 2, available: 1, reserved: 0, sold: 1, unitsWithBuyer: 1, withDetails: 2, withCover: 2, withStages: 2, withPrice: 2 },
      setup: [step('UNITS', true), step('TEAM', false), step('BUYER', false)],
    });

    const missing = element.querySelectorAll('.missing');
    expect(missing).toHaveLength(1);
    expect(missing[0].querySelector('a')?.getAttribute('href')).toBe('/obras/d1/equipo');

    const kpis = Array.from(element.querySelectorAll('.kpi')).map(text);
    expect(Array.from(element.querySelectorAll('.kpi .label')).map(label => text(label).trim()))
      .toEqual(['Avance del desarrollo', 'Casas en obra', 'Vendidas', 'Gasto', 'Entrega estimada']);
    expect(kpis[0]).toContain('22%');
    expect(kpis[1]).toContain('1 de 2');
    expect(kpis[1]).toContain('0 terminadas · 1 sin empezar');
    expect(kpis[2]).toContain('1 de 2');
    expect(kpis[3]).toContain('$ 250 mil');
    expect(kpis[3]).toContain('25% de US$ 1 M presupuestados');
    expect(kpis[4]).toContain('Jul 2099');
    // Los datos del barrio se ven en "Editar datos", no en el Resumen.
    expect(text(element)).not.toContain('Barrio cerrado al pie de las sierras.');

    const cards = Array.from(element.querySelectorAll('app-house-card')).map(text);
    expect(cards[0]).toContain('Casa 1');
    expect(cards[0]).toContain('Sin empezar · inicia en noviembre');
    expect(cards[0]).toContain('Sin encargado asignado');
    expect(cards[1]).toContain('Casa 2');
    expect(cards[1]).toContain('Cubierta y techo +2');
    expect(cards[1]).toContain('Alerta de gasto');
    expect(cards[1]).toContain('Carlos publicó un avance en Instalación eléctrica');
    expect(element.querySelectorAll('app-house-card')[1].querySelector('.dot.unseen')).not.toBeNull();

    const review = Array.from(element.querySelectorAll('.review')).map(text);
    expect(review).toHaveLength(2);
    expect(review[0]).toContain('Audio de Carlos · Casa 2');
    expect(review[0]).toContain('2 novedades, 1 cambio de etapa');
    expect(review[1]).toContain('Gastó 62% del presupuesto con 30% de avance');
    expect(element.querySelectorAll('app-activity-line')).toHaveLength(1);
  });

  it('sin datos faltantes no muestra la línea', () => {
    const element = render('OWNER', {
      developmentId: 'd1', kind: 'MULTI_UNIT', content, economic: null, commercial: null, houses: [],
      setup: [step('DETAILS', true), step('TEAM', true), step('FIRST_CAPTURE', false)],
    });

    expect(element.querySelector('.missing')).toBeNull();
    expect(text(element)).not.toContain('Faltan algunos datos');
  });

  it('al encargado: indicadores y casas, sin venta, gasto ni lo del costado', () => {
    const element = render('MEMBER', {
      developmentId: 'd1', kind: 'MULTI_UNIT', content, economic: null, commercial: null, setup: null,
      houses: houses.map(house => ({ ...house, unseen: null, hasSupervisor: null, alertLight: null, alertStageName: null })),
    });

    expect(element.querySelector('.missing')).toBeNull();
    expect(Array.from(element.querySelectorAll('.kpi .label')).map(label => text(label).trim()))
      .toEqual(['Promedio de tus casas', 'Casas en obra', 'Entrega estimada']);
    expect(element.querySelector('.side')).toBeNull();
    expect(text(element)).not.toContain('Vendida');
    expect(text(element)).not.toContain('Alerta de gasto');
    expect(text(element)).not.toContain('Sin encargado asignado');
  });
});

describe('WorkTeamComponent', () => {
  it('asigna a todo el desarrollo o a casas puntuales, cambia las casas y quita', () => {
    const work: Development = aDevelopment({ role: 'OWNER' });
    TestBed.configureTestingModule({
      imports: [WorkTeamComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { development: signal(work).asReadonly(), isOwner: computed(() => true) } },
      ],
    });
    const fixture = TestBed.createComponent(WorkTeamComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const houses = [aUnit({ id: 'c2', code: 'Casa 2' }), aUnit({ id: 'c1', code: 'Casa 1' })];
    const team = {
      assigned: [{ userId: 'u1', email: 'martin@obra.test', fullName: 'Martín Sosa', assignedAt: '2026-09-01T10:00:00Z', allHouses: false, unitIds: ['c1'] }],
      available: [{ userId: 'u2', email: 'ana@obra.test', fullName: 'Ana Ruiz', assignedAt: null, allHouses: false, unitIds: [] }],
    };
    const flushAll = () => {
      http.expectOne('/api/developments/d1/team').flush(team);
      http.expectOne('/api/organizations/o1/members').flush([]);
      http.expectOne('/api/developments/d1/units').flush(houses);
      fixture.detectChanges();
    };
    flushAll();
    const element = fixture.nativeElement as HTMLElement;
    const button = (label: string) => Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(b => text(b).includes(label))!;

    expect(text(element.querySelector('.people')!)).toContain('Martín Sosa');
    expect(text(element.querySelector('.scope')!)).toBe('Casa 1');
    expect(text(element.querySelector('#available')!)).toContain('Ana Ruiz');

    button('Asignar').click();
    const assign = http.expectOne(request => request.method === 'POST' && request.url === '/api/developments/d1/team');
    expect(assign.request.body).toEqual({ userId: 'u2', unitIds: [] });
    assign.flush(team);
    flushAll();

    button('Cambiar casas').click();
    fixture.detectChanges();
    const radios = element.querySelectorAll<HTMLInputElement>('.scope-editor input[type="radio"]');
    expect(radios[1].checked).toBe(true);
    const boxes = element.querySelectorAll<HTMLInputElement>('.scope-editor input[type="checkbox"]');
    expect(boxes).toHaveLength(2);
    boxes[1].click();
    fixture.detectChanges();
    button('Guardar casas').click();
    const change = http.expectOne(request => request.method === 'PUT');
    expect(change.request.url).toBe('/api/developments/d1/team/u1');
    expect(change.request.body).toEqual({ unitIds: ['c1', 'c2'] });
    change.flush(team);
    flushAll();

    button('Quitar').click();
    const unassign = http.expectOne('/api/developments/d1/team/u1');
    expect(unassign.request.method).toBe('DELETE');
  });
});

describe('Visor y reportes', () => {
  it('los ambientes salen en orden de aparición y los recorridos sin ambiente van al final', () => {
    expect(viewerZones([{ zone: null }, { zone: 'Living' }, { zone: 'Cocina' }, { zone: 'Living' }]))
      .toEqual(['Living', 'Cocina', NO_ZONE]);
  });

  it('el período por omisión de un reporte es el mes anterior completo', () => {
    expect(previousMonth(new Date(2026, 8, 13))).toEqual({ start: '2026-08-01', end: '2026-08-31' });
    expect(previousMonth(new Date(2026, 0, 5))).toEqual({ start: '2025-12-01', end: '2025-12-31' });
  });
});

describe('WorkAudioReviewComponent', () => {
  it('publica lo que deja el revisor, no lo que propuso el modelo', () => {
    const work: Development = aDevelopment({ role: 'MEMBER' });
    TestBed.configureTestingModule({
      imports: [WorkAudioReviewComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ extractionId: 'x1' }) } } },
        { provide: WorkStore, useValue: { development: signal(work).asReadonly(), isOwner: computed(() => false), reload: () => {} } },
      ],
    });
    const fixture = TestBed.createComponent(WorkAudioReviewComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/audio-extractions/x1').flush({
      id: 'x1', developmentId: 'd1', reviewStatus: 'READY_FOR_REVIEW', transcript: 'se termino la losa',
      proposal: {
        stageProposals: [{ stageId: 's1', proposedStatus: 'COMPLETED', rationale: 'se terminó', confidence: 'LOW' }],
        progressNotes: [{ stageId: 's1', description: 'Se terminó la losa', confidence: 'HIGH' }],
        supplyAlerts: [],
      },
      promptVersion: 'v1', modelId: 'mock', audioUrl: null, createdAt: '2026-09-10T12:00:00Z',
      reviewedBy: null, reviewedAt: null, reviewNotes: null, unitId: null,
    });
    http.expectOne('/api/developments/d1/stages').flush([
      aStage({ progressPercent: 50 }),
    ]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(text(element)).toContain('Confianza baja, conviene verificar');
    expect(text(element)).toContain('se termino la losa');

    const note = element.querySelector<HTMLTextAreaElement>('#note-text-0')!;
    note.value = 'Se terminó el hormigonado de la losa del primer piso';
    note.dispatchEvent(new Event('input'));
    Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => text(button).includes('Descartar') && button.closest('.item')?.querySelector('#proposal-stage-0'))!.click();
    fixture.detectChanges();
    Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => text(button).includes('Confirmar y publicar'))!.click();

    // Cada novedad es un avance; el cambio de estado descartado no viaja.
    const approve = http.expectOne('/api/audio-extractions/x1/approve');
    expect(approve.request.body.entries).toEqual([
      { stageId: 's1', description: 'Se terminó el hormigonado de la losa del primer piso', photoIds: [], stageUpdate: null },
    ]);
  });

  it('un cambio de estado aceptado viaja con la novedad de su etapa, sin porcentaje: lo pone la persona', () => {
    const work: Development = aDevelopment({ role: 'MEMBER' });
    TestBed.configureTestingModule({
      imports: [WorkAudioReviewComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ extractionId: 'x1' }) } } },
        { provide: WorkStore, useValue: { development: signal(work).asReadonly(), isOwner: computed(() => false), reload: () => {} } },
      ],
    });
    const fixture = TestBed.createComponent(WorkAudioReviewComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/audio-extractions/x1').flush({
      id: 'x1', developmentId: 'd1', reviewStatus: 'READY_FOR_REVIEW', transcript: 'arrancó la estructura',
      proposal: {
        stageProposals: [{ stageId: 's1', proposedStatus: 'IN_PROGRESS', rationale: 'arrancó', confidence: 'HIGH' }],
        progressNotes: [{ stageId: 's1', description: 'Arrancó la estructura', confidence: 'HIGH' }],
        supplyAlerts: [{ description: 'Faltan caños', confidence: 'HIGH' }],
      },
      promptVersion: 'v1', modelId: 'mock', audioUrl: null, createdAt: '2026-09-10T12:00:00Z',
      reviewedBy: null, reviewedAt: null, reviewNotes: null, unitId: null,
    });
    http.expectOne('/api/developments/d1/stages').flush([aStage({ progressPercent: 50 })]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(Array.from(element.querySelectorAll<HTMLOptionElement>('#proposal-status-0 option, select option'))
      .some(option => option.value === 'PENDING')).toBe(false);
    Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => text(button).includes('Confirmar y publicar'))!.click();

    const approve = http.expectOne('/api/audio-extractions/x1/approve');
    expect(approve.request.body.entries).toEqual([{
      stageId: 's1', description: 'Arrancó la estructura', photoIds: [],
      stageUpdate: { status: 'IN_PROGRESS', progressPercent: null },
    }]);
    expect(approve.request.body.supplyAlerts).toBeUndefined();
  });
});
