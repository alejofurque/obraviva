import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AssignedHouse } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { STANDARD_SECTORS, aStage, anActivityItem } from '../../testing/fixtures';
import { FieldActivityComponent } from './field-activity.component';
import { FieldHouseComponent } from './field-house.component';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function house(overrides: Partial<AssignedHouse> = {}): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Barrio Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: 'Modelo Aromo',
    progressPercent: 68, currentStageName: 'Revoques', currentStagePercent: 40, stageCount: 3, coverUrl: null, coverThumbUrl: null,
    sectors: STANDARD_SECTORS, lastEntryDate: null, lastEntryText: null, lastCaptureDate: null, capturedSectors: 0,
    capturesInProcess: 0, ...overrides,
  };
}

function providers() {
  return [
    provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
    { provide: LOCALE_ID, useValue: 'es-AR' },
    { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ unitId: 'u1' }), queryParamMap: convertToParamMap({}) } } },
    { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Carlos Medina' }).asReadonly() } },
  ];
}

describe('Ver la casa (encargado)', () => {
  let fixture: ComponentFixture<FieldHouseComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  afterEach(() => http.verify());

  function render(houses: AssignedHouse[] = [house()]): void {
    TestBed.configureTestingModule({ imports: [FieldHouseComponent], providers: providers() });
    fixture = TestBed.createComponent(FieldHouseComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-work').flush(houses);
    http.expectOne('/api/units/u1/stages').flush([
      aStage({ id: 's2', name: 'Estructura', orderIndex: 2, status: 'IN_PROGRESS', progressPercent: 40, unitId: 'u1',
        description: 'Columnas, vigas y losa.' }),
      aStage({ id: 's1', name: 'Fundaciones', orderIndex: 1, status: 'COMPLETED', progressPercent: 100, unitId: 'u1' }),
      aStage({ id: 's3', name: 'Revoques', orderIndex: 3, status: 'PENDING', progressPercent: 0, unitId: 'u1',
        description: 'Grueso y fino.' }),
    ]);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  it('las etapas en curso van primero, con la tarjeta de Etapas, "Qué incluye" y "Publicar avance"', () => {
    render();

    const cards = Array.from(element.querySelectorAll('app-stage-card'));
    expect(cards.map(card => text(card.querySelector('.name')!))).toEqual(['Estructura']);
    expect(text(cards[0])).toContain('En curso');
    expect(text(cards[0])).toContain('40%');
    const includes = cards[0].querySelector('details.ov-disclosure')!;
    expect(text(includes.querySelector('summary')!)).toBe('Qué incluye');
    expect(text(includes.querySelector('p')!)).toBe('Columnas, vigas y losa.');
    const publish = cards[0].querySelector('a.publish')!;
    expect(publish.classList).toContain('ov-btn');
    expect(publish.getAttribute('href')).toBe('/hoy/u1/avance?etapa=s2');
    expect(text(element.querySelector('.head')!)).toContain('1 de 3 etapas terminadas');
  });

  it('las demás van plegadas por estado, con el desplegable propio', () => {
    render();

    const folded = Array.from(element.querySelectorAll('details.folded'));
    expect(folded.map(group => text(group.querySelector('summary')!))).toEqual(['Por empezar · 1', 'Terminadas · 1']);
    expect(folded.every(group => group.classList.contains('ov-disclosure') && !group.hasAttribute('open'))).toBe(true);
    expect(text(folded[0].querySelector('.row-name')!)).toBe('Revoques');
    expect(text(folded[1].querySelector('.row-name')!)).toBe('Fundaciones');
    expect(folded[1].querySelector('a.publish')).toBeNull();
  });

  it('no muestra "Tu casa, lugar por lugar": es del comprador, y no pide su vista', () => {
    render();

    expect(element.querySelector('app-property-places')).toBeNull();
    expect(text(element)).not.toContain('lugar por lugar');
  });

  it('nada de venta: sin precio, compradores, enlaces ni estado comercial en la pantalla', () => {
    render();
    const page = text(element).toLowerCase();
    for (const word of ['precio', 'comprador', 'enlace', 'vendida', 'disponible', 'reservada', 'gasto', 'presupuesto']) {
      expect(page).not.toContain(word);
    }
  });

  it('una casa que no es suya lo dice', () => {
    render([house({ unitId: 'otra' })]);
    expect(text(element)).toContain('No estás asignado a esta casa.');
  });
});

describe('Mis publicaciones (encargado)', () => {
  let http: HttpTestingController;

  afterEach(() => http.verify());

  it('lo propio de la casa, por día y con "Ver más"; corregir y ver se quedan en Hoy, sin marca de visto', () => {
    TestBed.configureTestingModule({ imports: [FieldActivityComponent], providers: providers() });
    const fixture = TestBed.createComponent(FieldActivityComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-work').flush([house()]);
    const first = http.expectOne(req => req.url === '/api/developments/d1/activity');
    expect(first.request.params.get('unitId')).toBe('u1');
    expect(first.request.params.get('authorId')).toBe('me');
    first.flush({
      items: [anActivityItem({ id: 'entry:mio', authorId: 'me' })],
      nextCursor: '2026-10-19T10:00:00Z',
      seenUntil: null,
    });
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(text(element.querySelector('h1')!)).toBe('Mis publicaciones');
    const links = Array.from(element.querySelectorAll('app-activity-item a')).map(link => [text(link), link.getAttribute('href')]);
    expect(links).toEqual([['Ver en la casa', '/hoy/u1/casa'], ['Corregir', '/hoy/u1/avance/e2']]);

    const more = Array.from(element.querySelectorAll('button')).find(button => text(button) === 'Ver más')!;
    more.click();
    fixture.detectChanges();
    const second = http.expectOne(req => req.url === '/api/developments/d1/activity');
    expect(second.request.params.get('before')).toBe('2026-10-19T10:00:00Z');
    second.flush({ items: [anActivityItem({ id: 'entry:viejo', authorId: 'me' })], nextCursor: null, seenUntil: null });
    fixture.detectChanges();

    expect(element.querySelectorAll('app-activity-item')).toHaveLength(2);
    expect(Array.from(element.querySelectorAll('button')).some(button => text(button) === 'Ver más')).toBe(false);
    http.expectNone(req => req.url.includes('/activity/seen'));
  });
});
