import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivityItem, ActivityPage, PendingAudio } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { aDevelopment, aUnit, anActivityItem } from '../../testing/fixtures';
import { WorkActivityComponent } from './work-activity.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

/** Un instante de hoy, en la hora del navegador, con microsegundos como los manda el backend. */
const today = (time: string) => new Date(`${todayLocal()}T${time}`).toISOString().replace('Z', '337Z');

function page(items: ActivityItem[], nextCursor: string | null = null): ActivityPage {
  return { items, nextCursor, seenUntil: null };
}

describe('Novedades del desarrollo', () => {
  let fixture: ComponentFixture<WorkActivityComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;
  let cleared: number;

  function render(audios: PendingAudio[] = []): void {
    cleared = 0;
    TestBed.configureTestingModule({
      imports: [WorkActivityComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: {
            development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true),
            clearUnseen: () => cleared++,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(WorkActivityComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/activity/pending-audios').flush(audios);
    http.expectOne('/api/developments/d1/units').flush([aUnit({ id: 'u1', code: 'Casa 1' }), aUnit({ id: 'u2', code: 'Casa 2' })]);
    http.expectOne('/api/developments/d1/team').flush({
      assigned: [{ userId: 'p1', email: 'carlos@obraviva.test', fullName: 'Carlos Medina', assignedAt: null, allHouses: false, unitIds: ['u1'] }],
      available: [],
    });
    element = fixture.nativeElement as HTMLElement;
  }

  function feed(): TestRequest {
    return http.expectOne(request => request.url === '/api/developments/d1/activity');
  }

  afterEach(() => http.verify());

  it('marca visto hasta el ítem más nuevo mostrado, no hasta "ahora", y los puntos de esta visita se quedan', () => {
    render();
    const newest = today('15:00:00');
    feed().flush(page([
      anActivityItem({ id: 'a', at: newest, unseen: true }),
      anActivityItem({ id: 'b', at: today('09:00:00'), unseen: false }),
    ]));

    const seen = http.expectOne(request => request.method === 'PUT' && request.url === '/api/developments/d1/activity/seen');
    expect(seen.request.body).toEqual({ until: newest });
    expect(cleared).toBe(0);
    seen.flush(null);
    fixture.detectChanges();

    expect(cleared).toBe(1);
    const items = element.querySelectorAll('app-activity-item');
    expect(items[0].querySelector('.dot')).not.toBeNull();
    expect(items[1].querySelector('.dot')).toBeNull();
  });

  it('agrupa por día y arriba lista los audios para revisar, con acceso a "Revisar"', () => {
    render([{ extractionId: 'x1', unitId: 'u1', unitCode: 'Casa 1', authorId: 'p1', authorName: 'Carlos Medina', createdAt: today('10:00:00'), progressNotes: 2, stageChanges: 1 }]);
    feed().flush(page([anActivityItem({ at: today('15:00:00') }), anActivityItem({ id: 'viejo', at: '2026-01-05T15:00:00' })]));
    http.expectOne('/api/developments/d1/activity/seen').flush(null);
    fixture.detectChanges();

    const audios = element.querySelector('.audios')!;
    expect(text(audios)).toContain('Casa 1');
    expect(text(audios)).toContain('Carlos Medina');
    expect(audios.querySelector('a')!.getAttribute('href')).toBe('/obras/d1/casas/u1/historia/audios/x1');
    expect(Array.from(element.querySelectorAll('.day-title')).map(text)[0]).toBe('Hoy');
    expect(element.querySelectorAll('.day')).toHaveLength(2);
  });

  it('un aviso de materiales de un audio aprobado es su propio ítem: quién avisó, qué, la casa y que no se publicó', () => {
    render();
    feed().flush(page([anActivityItem({
      id: 'supply:x1', kind: 'SUPPLY', at: today('11:00:00'), entry: null,
      supply: { extractionId: 'x1', alerts: ['Falta el tablero eléctrico', 'Arena demorada'] },
    })]));
    http.expectOne('/api/developments/d1/activity/seen').flush(null);
    fixture.detectChanges();

    const item = element.querySelector('app-activity-item')!;
    expect(text(item.querySelector('.who')!)).toBe('Carlos Medina avisó: falta el tablero eléctrico y 1 aviso más');
    expect(text(item.querySelector('.unit')!)).toBe('Casa 1');
    expect(Array.from(item.querySelectorAll('.alerts li')).map(text)).toEqual(['Falta el tablero eléctrico', 'Arena demorada']);
    expect(text(item.querySelector('.note')!)).toBe('Aviso de materiales de un audio: no se publicó.');
    expect(item.querySelector('.avatar.supply')).not.toBeNull();
    expect(Array.from(item.querySelectorAll('a')).map(text)).toEqual(['Ver en la casa']);
  });

  it('sin audios pendientes no muestra el bloque', () => {
    render([]);
    feed().flush(page([anActivityItem({ at: today('15:00:00') })]));
    http.expectOne('/api/developments/d1/activity/seen').flush(null);
    fixture.detectChanges();

    expect(element.querySelector('.audios')).toBeNull();
  });

  it('los filtros vuelven a pedir con casa, encargado y tipo; filtrado no marca visto', () => {
    render();
    feed().flush(page([]));
    fixture.detectChanges();

    const [house, person] = Array.from(element.querySelectorAll<HTMLSelectElement>('select'));
    expect(Array.from(house.options).map(option => option.text)).toEqual(['Todas las casas', 'Casa 1', 'Casa 2']);
    house.value = 'u2';
    house.dispatchEvent(new Event('change'));
    let request = feed();
    expect(request.request.params.get('unitId')).toBe('u2');
    request.flush(page([anActivityItem({ at: today('15:00:00') })]));

    person.value = 'p1';
    person.dispatchEvent(new Event('change'));
    request = feed();
    expect(request.request.params.get('unitId')).toBe('u2');
    expect(request.request.params.get('authorId')).toBe('p1');
    request.flush(page([]));
    fixture.detectChanges();

    Array.from(element.querySelectorAll<HTMLButtonElement>('.filters button')).find(button => text(button) === 'Audio')!.click();
    request = feed();
    expect(request.request.params.get('kind')).toBe('AUDIO');
    request.flush(page([]));
    fixture.detectChanges();

    // Ninguna carga filtrada marcó visto: lo más nuevo mostrado no era lo más nuevo.
    http.expectNone('/api/developments/d1/activity/seen');
  });

  it('vacío lo dice; con filtros, que no hay con esos filtros', () => {
    render();
    feed().flush(page([]));
    fixture.detectChanges();

    expect(text(element.querySelector('app-empty-state')!)).toContain('Todavía no hay novedades');
    expect(text(element.querySelector('app-empty-state')!)).toContain('Lo que publiquen los encargados aparece acá.');
    // Sin nada que mostrar no hay qué marcar; el contador igual queda en cero.
    http.expectNone('/api/developments/d1/activity/seen');
    expect(cleared).toBe(1);

    Array.from(element.querySelectorAll<HTMLButtonElement>('.filters button')).find(button => text(button) === '3D')!.click();
    feed().flush(page([]));
    fixture.detectChanges();
    expect(text(element.querySelector('app-empty-state')!)).toContain('No hay novedades con estos filtros');
  });

  it('"Ver más" pide la página siguiente con el cursor y la suma; sin cursor, el botón no está', () => {
    render();
    feed().flush(page([anActivityItem({ id: 'a', at: today('15:00:00') })], '2026-10-01T10:00:00Z'));
    http.expectOne('/api/developments/d1/activity/seen').flush(null);
    fixture.detectChanges();

    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(button => text(button) === 'Ver más')!.click();
    const next = feed();
    expect(next.request.params.get('before')).toBe('2026-10-01T10:00:00Z');
    next.flush(page([anActivityItem({ id: 'b', at: '2026-09-30T10:00:00' })]));
    fixture.detectChanges();

    expect(element.querySelectorAll('app-activity-item')).toHaveLength(2);
    expect(Array.from(element.querySelectorAll('button')).some(button => text(button) === 'Ver más')).toBe(false);
    // La página siguiente no vuelve a marcar.
    http.expectNone('/api/developments/d1/activity/seen');
  });
});
