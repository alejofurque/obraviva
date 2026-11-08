import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { ActivityItem } from '../../core/api/api.models';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AssignedHouse, Stage } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { STANDARD_SECTORS, aMedia, aStage, anActivityItem } from '../../testing/fixtures';
import { todayLocal } from '../../shared/time/local-date';
import { zonesOf } from '../property/property-derivations';
import { addSector, moveSector } from '../works/sectors-editor.component';
import {
  audioNotice, audioNoticeLine, firstName, lastPublicationLine, sessionProgress, sessionStatus, shortWhen, todayHeading, workingStage,
} from './field-derivations';
import { FieldHomeComponent } from './field-home.component';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
/** El texto de cada hijo separado por un espacio: Angular no deja espacios entre elementos. */
const joined = (element: Element) => Array.from(element.children).map(text).filter(Boolean).join(' ');

function house(overrides: Partial<AssignedHouse> = {}): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Barrio Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: 'Modelo Aromo',
    progressPercent: 68, currentStageName: 'Revoques', currentStagePercent: 40, stageCount: 12, coverUrl: null, coverThumbUrl: null, sectors: STANDARD_SECTORS,
    lastEntryDate: '2026-09-08', lastEntryText: 'Revoque grueso terminado.', lastCaptureDate: '2026-09-05',
    capturedSectors: 2, capturesInProcess: 0,
    ...overrides,
  };
}

describe('Trabajo de campo', () => {
  it('saluda por el nombre y trabaja sobre la etapa en curso o la primera pendiente', () => {
    expect(firstName('Carlos Medina')).toBe('Carlos');
    expect(firstName('')).toBeNull();
    expect(workingStage([
      aStage({ id: 'a', orderIndex: 1, status: 'COMPLETED' }),
      aStage({ id: 'b', orderIndex: 2, status: 'PENDING' }),
    ])?.id).toBe('b');
    expect(workingStage([aStage({ id: 'x', status: 'DELAYED' }), aStage({ id: 'y', orderIndex: 2, status: 'PENDING' })])?.id).toBe('x');
  });

  it('cada sector de la fecha queda grabado, en proceso, fallido o por grabar', () => {
    const statuses = sessionStatus(
      STANDARD_SECTORS,
      [{ unitId: 'u1', captureDate: '2026-09-19', zone: 'Frente' }, { unitId: 'u2', captureDate: '2026-09-19', zone: 'Baño' }],
      [
        { unitId: 'u1', captureDate: '2026-09-19', zone: 'Living y cocina', jobStatus: 'PROCESSING' },
        { unitId: 'u1', captureDate: '2026-09-19', zone: 'Baño', jobStatus: 'FAILED' },
      ],
      'u1',
      '2026-09-19',
    );

    expect(statuses.map(status => status.state)).toEqual(['DONE', 'PROCESSING', 'MISSING', 'FAILED']);
    expect(sessionProgress(statuses)).toEqual({ sent: 2, total: 4 });
  });

  it('los sectores no se repiten, tienen tope y se reordenan', () => {
    expect(addSector(['Frente'], '  frente ')).toEqual(['Frente']);
    expect(addSector(['Frente'], ' Planta   alta ')).toEqual(['Frente', 'Planta alta']);
    expect(addSector(Array.from({ length: 12 }, (_, i) => 'S' + i), 'Otro')).toHaveLength(12);
    expect(moveSector(['A', 'B', 'C'], 2, -1)).toEqual(['A', 'C', 'B']);
    expect(moveSector(['A', 'B'], 0, -1)).toEqual(['A', 'B']);
  });

  it('en el recorrido del comprador, los sectores van en su orden y lo demás después', () => {
    const capture = (zone: string) => ({ zone }) as never;
    expect(zonesOf([capture('Baño'), capture('Club house'), capture('Frente')], STANDARD_SECTORS))
      .toEqual(['Frente', 'Baño', 'Club house']);
  });
});

describe('Aviso de audios en Hoy', () => {
  const audio = (id: string, overrides: object) => ({
    id, developmentId: 'd1', reviewStatus: 'READY_FOR_REVIEW', transcriptPreview: null, proposedElements: 0,
    createdAt: '2026-10-14T13:05:00Z', unitId: 'u1', jobStatus: null, pipelineStep: null, reviewNotes: null, mine: true,
    ...overrides,
  }) as never;

  it('cuenta sólo los propios de la casa, listos primero el más viejo; los que se procesan aparte', () => {
    const notice = audioNotice([
      audio('nuevo', { createdAt: '2026-10-15T10:00:00Z' }),
      audio('viejo', { createdAt: '2026-10-13T10:00:00Z' }),
      audio('procesando', { reviewStatus: 'PENDING' }),
      audio('ajeno', { mine: false }),
      audio('otra-casa', { unitId: 'u2' }),
      audio('aprobado', { reviewStatus: 'APPROVED' }),
    ], 'u1');
    expect(notice.ready.map(item => item.id)).toEqual(['viejo', 'nuevo']);
    expect(notice.processing.map(item => item.id)).toEqual(['procesando']);
  });

  it('lo dice en singular o plural', () => {
    expect(audioNoticeLine(1)).toBe('Tenés 1 audio para revisar');
    expect(audioNoticeLine(3)).toBe('Tenés 3 audios para revisar');
  });
});

describe('FieldHomeComponent', () => {
  afterEach(() => http?.verify());

  let http: HttpTestingController;
  let fixture: ComponentFixture<FieldHomeComponent>;

  interface Support {
    audios?: unknown[];
    media?: unknown[];
    stages?: Stage[];
    latest?: ActivityItem[];
  }

  /** Lo de una casa: audios, etapas, su última publicación y la sesión del día. */
  function flushSupport(unitId: string, support: Support = {}): void {
    http.expectOne('/api/developments/d1/audio-extractions').flush(support.audios ?? []);
    http.expectOne(`/api/units/${unitId}/stages`).flush(support.stages ?? []);
    http.expectOne(req => req.url === '/api/developments/d1/activity' && req.params.get('unitId') === unitId
      && req.params.get('authorId') === 'me' && req.params.get('limit') === '5')
      .flush({ items: support.latest ?? [], nextCursor: null, seenUntil: null });
    http.expectOne('/api/developments/d1/captures').flush([]);
    http.expectOne('/api/developments/d1/captures/processing').flush([]);
    http.expectOne('/api/developments/d1/media').flush(support.media ?? []);
    fixture.detectChanges();
  }

  function render(houses: AssignedHouse[], support: Support = {}): HTMLElement {
    try {
      localStorage.removeItem('obraviva.field.house.me');
    } catch {
      // Sin almacenamiento, el test sigue igual.
    }
    TestBed.configureTestingModule({
      imports: [FieldHomeComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Carlos Medina', email: 'carlos@obraviva.test' }).asReadonly() } },
      ],
    });
    fixture = TestBed.createComponent(FieldHomeComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-work').flush(houses);
    fixture.detectChanges();
    if (houses.length > 0) {
      flushSupport(houses[0].unitId, support);
    }
    return fixture.nativeElement as HTMLElement;
  }

  it('la fecha y el saludo; la casa como tarjeta; "Publicar avance" primero y el recorrido con cuántos lugares tienen material', () => {
    const element = render([house(), house({ unitId: 'u2', code: 'Casa 2' })], {
      media: [
        aMedia({ id: 'm1', unitId: 'u1', sector: 'Frente', progressEntryId: null, takenOn: todayLocal() }),
        aMedia({ id: 'm2', unitId: 'u1', sector: 'Baño', progressEntryId: null, takenOn: '2026-01-01' }),
      ],
    });

    expect(text(element.querySelector('.date')!)).toBe(todayHeading());
    expect(text(element.querySelector('h1')!)).toBe('Hola, Carlos');
    expect(element.querySelector('app-account-menu')).not.toBeNull();
    expect(element.querySelector('app-field-house-picker button.house')!.getAttribute('aria-label'))
      .toBe('Casa 1, Barrio Los Aromos. Cambiar de casa');
    const primary = element.querySelector('.actions .primary')!;
    expect(text(primary)).toContain('Publicar avance');
    expect(primary.getAttribute('href')).toBe('/hoy/u1/avance');
    const cards = Array.from(element.querySelectorAll('.actions .card'));
    expect(cards.map(card => card.getAttribute('href'))).toEqual(['/hoy/u1/recorrido', '/hoy/u1/audio']);
    expect(joined(cards[0])).toBe('Recorrido de hoy 3D y fotos · 1 de 4 lugares');
    // Cada pista es de su tamaño: la del teléfono y la de escritorio (el CSS muestra una).
    expect(joined(cards[1])).toBe('Contarlo con audio La IA lo ordena, vos revisás O subí un audio de WhatsApp');
  });

  it('con una sola casa la tarjeta no ofrece cambiarla', () => {
    const element = render([house()]);
    expect(element.querySelector('app-field-house-picker button')).toBeNull();
    expect(text(element.querySelector('app-field-house-picker')!)).toContain('Casa 1');
  });

  it('cambiar de casa: elige otra, la recuerda y trae lo de esa casa', () => {
    const element = render([house(), house({ unitId: 'u2', code: 'Casa 2' })]);

    const option = Array.from(element.querySelectorAll<HTMLButtonElement>('app-field-house-picker .option'))
      .find(button => text(button).startsWith('Casa 2'))!;
    option.click();
    fixture.detectChanges();
    flushSupport('u2');

    expect(element.querySelector('.actions .primary')!.getAttribute('href')).toBe('/hoy/u2/avance');
    expect(localStorage.getItem('obraviva.field.house.me')).toBe('u2');
  });

  it('las etapas en curso con su avance, en orden de obra; la demorada lo dice', () => {
    const element = render([house()], {
      stages: [
        aStage({ id: 's8', name: 'Revoques', orderIndex: 8, progressPercent: 75 }),
        aStage({ id: 's1', name: 'Fundaciones', orderIndex: 2, status: 'COMPLETED', progressPercent: 100 }),
        aStage({ id: 's6', name: 'Sanitaria', orderIndex: 6, status: 'DELAYED', progressPercent: 55 }),
        aStage({ id: 's9', name: 'Aberturas', orderIndex: 9, status: 'PENDING', progressPercent: 0 }),
      ],
    });

    const rows = Array.from(element.querySelectorAll('.stages app-field-stages .stage'));
    expect(rows.map(joined)).toEqual(['Sanitaria Demorada 55%', 'Revoques 75%']);
    expect(rows[0].classList).toContain('delayed');
    expect(element.querySelector('.stages app-field-stages .publish')).toBeNull();
    // Al costado, en escritorio, cada una con "Publicar", que llega con esa etapa elegida.
    const publish = Array.from(element.querySelectorAll('app-field-house-aside .publish'));
    expect(publish.map(link => link.getAttribute('href'))).toEqual(['/hoy/u1/avance?etapa=s6', '/hoy/u1/avance?etapa=s8']);
  });

  it('en escritorio, la casa al costado sin otro selector (se cambia en la barra) y "Ver la casa completa"', () => {
    const element = render([house(), house({ unitId: 'u2', code: 'Casa 2' })]);
    const aside = element.querySelector('app-field-house-aside')!;
    expect(text(aside.querySelector('.code')!)).toBe('Casa 1');
    expect(aside.querySelector('button, select')).toBeNull();
    expect(Array.from(aside.querySelectorAll('a')).at(-1)!.getAttribute('href')).toBe('/hoy/u1/casa');
    // El selector de la tarjeta es sólo del teléfono.
    expect(element.querySelector('app-field-house-picker')!.classList).toContain('phone-only');
    expect(aside.classList).toContain('desktop-only');
  });

  it('"Mis últimas publicaciones" en escritorio: las cinco últimas, compactas, con "Corregir" en los avances', () => {
    const now = new Date().toISOString();
    const element = render([house()], {
      latest: [
        anActivityItem({ id: 'entry:r1', authorId: 'me', at: now }),
        anActivityItem({ id: 'photos:1', kind: 'PHOTOS', authorId: 'me', at: '2026-10-14T15:00:00', entry: null,
          session: { day: '2026-10-14', photos: 9, videos: 0, sectors: ['Frente', 'Living', 'Cocina', 'Baño'], thumbnails: [] } }),
      ],
    });
    const rows = Array.from(element.querySelectorAll('app-field-recent .row'));
    expect(rows).toHaveLength(2);
    expect(text(rows[0].querySelector('.when')!)).toBe('Hoy');
    expect(text(rows[0].querySelector('.tag')!)).toBe('Instalación eléctrica');
    expect(rows[0].querySelector('a.correct')!.getAttribute('href')).toBe('/hoy/u1/avance/e2');
    expect(text(rows[1].querySelector('.when')!)).toBe('14 oct');
    expect(text(rows[1].querySelector('.tag')!)).toBe('Fotos · 4 lugares');
    expect(text(rows[1].querySelector('.text')!)).toBe('9 fotos del recorrido: Frente, Living, Cocina y 1 más.');
    expect(rows[1].querySelector('a.correct')).toBeNull();
    expect(element.querySelector('app-field-recent a.all')!.getAttribute('href')).toBe('/hoy/u1/publicaciones');
  });

  it('su última publicación en una línea, que lleva a "Mis publicaciones"', () => {
    const at = new Date().toISOString();
    const element = render([house()], { latest: [anActivityItem({ authorId: 'me', at })] });

    const last = element.querySelector('.last')!;
    // Sin "·" antes del enlace: al cortarse el renglón no queda colgando.
    expect(text(last.querySelector('span')!)).toBe('Tu última publicación: hoy · Instalación eléctrica');
    expect(text(last.querySelector('a')!)).toBe('Mis publicaciones');
    expect(last.querySelector('a')!.getAttribute('href')).toBe('/hoy/u1/publicaciones');
  });

  it('sin publicaciones propias lo dice, y "Mis publicaciones" sigue', () => {
    const element = render([house()]);
    expect(text(element.querySelector('.last span')!)).toBe('Todavía no publicaste nada en esta casa');
    expect(text(element.querySelector('app-field-recent .empty')!)).toBe('Todavía no publicaste nada en esta casa.');
  });

  it('arriba de las acciones, el aviso en ámbar de los audios propios listos para revisar', () => {
    const audio = (id: string, overrides: object) => ({
      id, developmentId: 'd1', reviewStatus: 'PENDING', transcriptPreview: null, proposedElements: 0,
      createdAt: '2026-10-14T13:05:00Z', unitId: 'u1', jobStatus: 'PROCESSING', pipelineStep: 'TRANSCRIBE',
      reviewNotes: null, mine: true, ...overrides,
    });
    const element = render([house()], {
      audios: [
        audio('x1', {}),
        audio('x2', { reviewStatus: 'READY_FOR_REVIEW', jobStatus: 'NEEDS_REVIEW', pipelineStep: 'AWAIT_REVIEW' }),
        audio('x3', { reviewStatus: 'APPROVED' }),
        audio('x4', { mine: false }),
        audio('x5', { unitId: 'u9' }),
      ],
    });

    const notice = element.querySelector('.notice')!;
    expect(joined(notice)).toBe('Tenés 1 audio para revisar Revisar');
    expect(notice.getAttribute('href')).toBe('/hoy/u1/audio/x2');
    expect(notice.compareDocumentPosition(element.querySelector('.actions')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(element.querySelector('.processing')?.getAttribute('href')).toBe('/hoy/u1/audio/x1');
  });

  it('sin casas asignadas lo explica', () => {
    expect(text(render([]))).toContain('Todavía no tenés casas asignadas');
  });
});

describe('Hoy: lecturas', () => {
  it('la fecha del saludo, con el día de la semana', () => {
    expect(todayHeading(new Date(2026, 9, 27))).toBe('Martes 27 de octubre');
  });

  it('cuándo fue lo último: hoy, ayer o el día corto, con lo que fue', () => {
    const today = '2026-10-27';
    expect(shortWhen('2026-10-27T15:00:00', today)).toBe('hoy');
    expect(shortWhen('2026-10-26T15:00:00', today)).toBe('ayer');
    expect(shortWhen('2026-10-14T15:00:00', today)).toBe('14 oct');
    const photos = anActivityItem({ kind: 'PHOTOS', at: '2026-10-14T15:00:00', entry: null,
      session: { day: '2026-10-14', photos: 9, videos: 0, sectors: [], thumbnails: [] } });
    expect(lastPublicationLine(photos, today)).toBe('14 oct · 9 fotos');
    const general = anActivityItem({ at: '2026-10-27T10:00:00' });
    general.entry!.stageName = null;
    expect(lastPublicationLine(general, today)).toBe('hoy · General de la casa');
  });
});
