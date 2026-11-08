import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AssignedHouse, Stage } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { STANDARD_SECTORS, aStage, anEntry } from '../../testing/fixtures';
import { entryDateLine, stageChips } from './field-derivations';
import { FieldHouseStore } from './field-house.store';
import { FieldProgressComponent, clampPercent, proposedStatus } from './field-progress.component';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function house(overrides: Partial<AssignedHouse> = {}): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Barrio Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: 'Modelo Aromo',
    progressPercent: 40, currentStageName: 'Estructura', currentStagePercent: 40, stageCount: 3, coverUrl: null, coverThumbUrl: null, sectors: STANDARD_SECTORS,
    lastEntryDate: null, lastEntryText: null, lastCaptureDate: null, capturedSectors: 0, capturesInProcess: 0,
    ...overrides,
  };
}

const STAGES: Stage[] = [
  aStage({ id: 's3', name: 'Mampostería', orderIndex: 3, status: 'PENDING', progressPercent: 0, unitId: 'u1' }),
  aStage({ id: 's1', name: 'Fundaciones', orderIndex: 1, status: 'COMPLETED', progressPercent: 100, unitId: 'u1' }),
  aStage({ id: 's2', name: 'Estructura', orderIndex: 2, status: 'IN_PROGRESS', progressPercent: 40, unitId: 'u1' }),
];

describe('Publicar avance: reglas', () => {
  it('el porcentaje es entero, entre 0 y 100', () => {
    expect(clampPercent(105)).toBe(100);
    expect(clampPercent(-5)).toBe(0);
    expect(clampPercent(42.6)).toBe(43);
    expect(clampPercent(Number('abc'))).toBe(0);
  });

  it('propone el estado que la etapa tiene, y En ejecución si estaba pendiente', () => {
    expect(proposedStatus({ status: 'PENDING' })).toBe('IN_PROGRESS');
    expect(proposedStatus({ status: 'DELAYED' })).toBe('DELAYED');
  });

  it('los chips son las etapas que se trabajan, más la elegida si es otra, en orden de obra', () => {
    const stages = [...STAGES, aStage({ id: 's4', name: 'Techo', orderIndex: 4, status: 'DELAYED', progressPercent: 20 })];
    expect(stageChips(stages, 's2').map(stage => stage.id)).toEqual(['s2', 's4']);
    expect(stageChips(stages, 's3').map(stage => stage.id)).toEqual(['s2', 's3', 's4']);
    expect(stageChips(stages, '').map(stage => stage.id)).toEqual(['s2', 's4']);
  });

  it('la fecha del avance: hoy, ayer o el día corto', () => {
    expect(entryDateLine('2026-10-27', '2026-10-27')).toBe('hoy, 27 oct');
    expect(entryDateLine('2026-10-26', '2026-10-27')).toBe('ayer, 26 oct');
    expect(entryDateLine('2026-09-01', '2026-10-27')).toBe('1 sep');
  });
});

describe('FieldProgressComponent', () => {
  let fixture: ComponentFixture<FieldProgressComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:foto');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => http.verify());

  function render(houses: AssignedHouse[] = [house()], stages: Stage[] = STAGES, queryParams: Record<string, string> = {}, entryId?: string): void {
    TestBed.configureTestingModule({
      imports: [FieldProgressComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Carlos Medina', email: 'carlos@obraviva.test' }).asReadonly() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(entryId ? { unitId: 'u1', entryId } : { unitId: 'u1' }), queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    fixture = TestBed.createComponent(FieldProgressComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-work').flush(houses);
    http.expectOne('/api/units/u1/stages').flush(stages);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const query = <T extends Element>(selector: string) => element.querySelector<T>(selector)!;
  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;
  const publishButton = () => button('Publicar avance');
  const chips = () => Array.from(element.querySelectorAll<HTMLButtonElement>('.chips .chip'));
  const pressed = () => chips().filter(chip => chip.getAttribute('aria-pressed') === 'true').map(text);
  const chip = (label: string) => chips().find(item => text(item).startsWith(label))!;
  const percentText = () => text(query('#field-percent'));

  function write(description: string): void {
    const area = query<HTMLTextAreaElement>('#field-text');
    area.value = description;
    area.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function pick(...names: string[]): void {
    const input = query<HTMLInputElement>('input[type=file]');
    const files = names.map(name => new File([new Uint8Array(10)], name, { type: 'image/jpeg' }));
    Object.defineProperty(input, 'files', { value: files, configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  /** La URL firmada de una foto pendiente, y su subida con progreso. */
  function uploadUrl(mediaId: string): TestRequest {
    const request = http.expectOne(req => req.url === '/api/developments/d1/media/upload-url'
      && req.body.unitId === 'u1' && !('progressEntryId' in req.body) && req.body.filename === mediaId + '.jpg');
    request.flush({ mediaId, uploadUrl: 'https://almacenamiento.test/' + mediaId, contentType: 'image/jpeg' });
    return http.expectOne('https://almacenamiento.test/' + mediaId);
  }

  function publish(): TestRequest {
    publishButton().click();
    fixture.detectChanges();
    return http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/progress');
  }

  it('la casa en el encabezado y la etapa en ejecución elegida entre chips con su avance', () => {
    render([house(), house({ unitId: 'u2', code: 'Casa 2' })]);

    expect(text(query('.sub'))).toBe('Casa 1 · Barrio Los Aromos');
    expect(element.querySelector('select')).toBeNull();
    expect(query('a.back').getAttribute('href')).toBe('/hoy');
    expect(chips().map(text)).toEqual(['Estructura 40%', 'General de la casa', 'Otra etapa…']);
    expect(pressed()).toEqual(['Estructura 40%']);
    expect(text(query('.date-line span'))).toBe(`Fecha: ${entryDateLine(todayLocal(), todayLocal())}`);
    expect(query('[role=switch]')).not.toBeNull();
  });

  it('"Otra etapa…" ofrece las pendientes y las terminadas; la elegida pasa a ser un chip', () => {
    render();
    chip('Otra etapa').click();
    fixture.detectChanges();

    const other = query<HTMLSelectElement>('#field-stage-other');
    expect(Array.from(other.options).slice(1).map(option => option.text)).toEqual(['1. Fundaciones (terminada)', '3. Mampostería']);
    other.value = 's3';
    other.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(element.querySelector('#field-stage-other')).toBeNull();
    expect(pressed()).toEqual(['Mampostería 0%']);
    expect(chips().map(text)).toEqual(['Estructura 40%', 'Mampostería 0%', 'General de la casa', 'Otra etapa…']);
  });

  it('desde una etapa de "Ver la casa" (?etapa=) llega con esa etapa elegida', () => {
    render([house()], STAGES, { etapa: 's3' });
    expect(pressed()).toEqual(['Mampostería 0%']);
  });

  it('una etapa pedida que no es de esta casa deja la que está en ejecución', () => {
    render([house()], STAGES, { etapa: 'otra' });
    expect(pressed()).toEqual(['Estructura 40%']);
  });

  it('sin texto no se publica; con texto y sin tocar la etapa, un solo envío sin stageUpdate, y después "Publicado"', () => {
    render();
    expect(publishButton().disabled).toBe(true);
    expect(text(query('.publish-bar'))).toContain('Contá qué se hizo');

    write('  Terminamos el encofrado de la losa.  ');
    const invalidate = vi.spyOn(TestBed.inject(FieldHouseStore), 'invalidate');
    const request = publish();

    expect(request.request.body).toEqual({
      stageId: 's2', entryDate: todayLocal(), description: 'Terminamos el encofrado de la losa.', unitId: 'u1',
      photoIds: [], stageUpdate: null, photoSectors: {},
    });
    expect(invalidate).not.toHaveBeenCalled();
    request.flush({ id: 'e1' });
    fixture.detectChanges();
    expect(text(query('h1'))).toBe('Publicado');
    // Hoy vuelve a pedir las casas: el avance de esta pudo cambiar.
    expect(invalidate).toHaveBeenCalledTimes(1);
    const links = Array.from(element.querySelectorAll<HTMLAnchorElement>('.done a'));
    expect(links.map(text)).toEqual(['Ver cómo lo ve el comprador', 'Volver a Hoy']);
    expect(links[0].getAttribute('href')).toBe('/mi-propiedad/u1?obra=d1&desde=hoy');
    expect(links[1].getAttribute('href')).toBe('/hoy');
  });

  it('"General de la casa" publica sin etapa y sin el bloque de la etapa', () => {
    render();
    chip('General de la casa').click();
    fixture.detectChanges();

    expect(pressed()).toEqual(['General de la casa']);
    expect(query('[role=switch]')).toBeNull();
    write('Llegó el hierro.');
    const request = publish();
    expect(request.request.body.stageId).toBeNull();
    expect(request.request.body.stageUpdate).toBeNull();
    request.flush({ id: 'e1' });
  });

  it('cada foto tiene un chip de lugar, precargado con el último elegido, y el lugar viaja con el avance', () => {
    try {
      localStorage.setItem('obraviva.field.sector.me.u1', 'Frente');
    } catch {
      // Sin almacenamiento el chip arranca vacío: el test sigue igual con la elección a mano.
    }
    render();
    write('Revoque del frente.');
    pick('m1.jpg', 'm2.jpg');
    uploadUrl('m1').flush(null);
    uploadUrl('m2').flush(null);
    fixture.detectChanges();

    const chips = Array.from(element.querySelectorAll<HTMLSelectElement>('app-sector-chip select'));
    expect(chips).toHaveLength(2);
    expect(Array.from(chips[0].options).map(option => option.text)).toEqual(['Sin lugar', ...STANDARD_SECTORS]);
    chips[0].value = 'Frente';
    chips[0].dispatchEvent(new Event('change'));
    chips[1].value = '';
    chips[1].dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const request = publish();
    expect(request.request.body.photoSectors).toEqual({ m1: 'Frente' });
    request.flush({ id: 'e1' });
    try {
      localStorage.removeItem('obraviva.field.sector.me.u1');
    } catch {
      // Nada que limpiar.
    }
  });

  it('las fotos suben apenas se eligen, con progreso, y no se publica hasta que terminaron; van por id en el mismo envío', () => {
    render();
    write('Revoque grueso.');
    pick('m1.jpg', 'm2.jpg');

    const first = uploadUrl('m1');
    const second = uploadUrl('m2');
    first.event({ type: HttpEventType.UploadProgress, loaded: 40, total: 100 });
    fixture.detectChanges();
    expect(query('[role=progressbar]').getAttribute('aria-valuenow')).toBe('40');
    expect(publishButton().disabled).toBe(true);
    expect(text(query('.publish-bar'))).toContain('Esperando que termine de subir 2 fotos');
    second.flush(null);
    fixture.detectChanges();
    expect(text(query('.publish-bar'))).toContain('Esperando que termine de subir 1 foto');

    first.flush(null);
    fixture.detectChanges();
    expect(element.querySelectorAll('[role=progressbar]')).toHaveLength(0);
    expect(element.querySelectorAll('.ok')).toHaveLength(2);
    expect(text(query('app-field-photo-grid .count'))).toBe('2 de 10');
    expect(publishButton().disabled).toBe(false);
    http.expectNone('/api/media/m1/confirm');

    const request = publish();
    expect(request.request.body.photoIds).toEqual(['m1', 'm2']);
    request.flush({ id: 'e1' });
  });

  it('una foto que falla se reintenta o se quita; mientras tanto no se publica', () => {
    render();
    write('Revoque grueso.');
    pick('m1.jpg');

    uploadUrl('m1').flush('caído', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(text(query('.failed'))).toBe('No subió');
    expect(publishButton().disabled).toBe(true);
    expect(text(query('.publish-bar'))).toContain('Reintentá o quitá');

    button('Reintentar').click();
    fixture.detectChanges();
    uploadUrl('m1').flush(null);
    fixture.detectChanges();
    expect(query('.failed')).toBeNull();
    expect(publishButton().disabled).toBe(false);

    query<HTMLButtonElement>('[aria-label="Quitar foto 1"]').click();
    fixture.detectChanges();
    expect(element.querySelectorAll('app-field-photo-grid li[data-state]')).toHaveLength(0);
    expect(text(query('app-field-photo-grid .count'))).toBe('0 de 10');
  });

  it('actualizar la etapa: el porcentaje actual precargado, ±5, aviso si baja, y el estado elegido en el mismo envío', () => {
    render();
    write('Hormigonamos columnas.');
    const toggle = query<HTMLInputElement>('[role=switch]');
    expect(toggle.checked).toBe(false);
    toggle.click();
    fixture.detectChanges();

    expect(percentText()).toBe('40%');
    expect(text(query('.before'))).toBe('antes 40%');
    button('+5').click();
    button('+5').click();
    fixture.detectChanges();
    expect(percentText()).toBe('50%');

    for (let i = 0; i < 4; i++) {
      button('−5').click();
    }
    fixture.detectChanges();
    expect(percentText()).toBe('30%');
    expect(text(query('.warning'))).toBe('Baja el avance que ve el comprador: estaba en 40%.');
    expect(publishButton().disabled).toBe(false);

    query<HTMLInputElement>('input[value=DELAYED]').click();
    fixture.detectChanges();
    expect(text(query('.statuses'))).toContain('El texto del avance es la explicación que ve el comprador');

    const request = publish();
    expect(request.request.body.stageUpdate).toEqual({ progressPercent: 30, status: 'DELAYED' });
    request.flush({ id: 'e1' });
    fixture.detectChanges();
    expect(text(query('.done'))).toContain('Estructura al 30%');
  });

  it('Completado lleva a 100 y lo bloquea; volver a En ejecución devuelve el número que había', () => {
    render();
    query<HTMLInputElement>('[role=switch]').click();
    fixture.detectChanges();
    button('+5').click();
    fixture.detectChanges();

    query<HTMLInputElement>('input[value=COMPLETED]').click();
    fixture.detectChanges();
    expect(percentText()).toBe('100%');
    expect(button('+5').disabled).toBe(true);
    expect(button('−5').disabled).toBe(true);
    expect(text(query('.update'))).toContain('Completada, la etapa queda en 100%.');

    query<HTMLInputElement>('input[value=IN_PROGRESS]').click();
    fixture.detectChanges();
    expect(percentText()).toBe('45%');
    expect(button('−5').disabled).toBe(false);
  });

  it('la fecha se despliega con "cambiar" y no puede ser futura', () => {
    render();
    write('Algo.');
    button('Cambiar').click();
    fixture.detectChanges();

    const date = query<HTMLInputElement>('#field-date');
    expect(date.max).toBe(todayLocal());
    date.value = '2999-01-01';
    date.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text(query('.date .problem'))).toBe('La fecha no puede ser futura.');
    expect(publishButton().disabled).toBe(true);

    date.value = '2026-09-01';
    date.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const request = publish();
    expect(request.request.body.entryDate).toBe('2026-09-01');
    request.flush({ id: 'e1' });
  });

  it('si falla la red no se pierde lo escrito, y "Reintentar" vuelve a mandar lo mismo', () => {
    render();
    write('Colocamos las aberturas.');
    query<HTMLInputElement>('[role=switch]').click();
    fixture.detectChanges();

    publish().error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(query('[role=alert]')).not.toBeNull();
    expect(query<HTMLTextAreaElement>('#field-text').value).toBe('Colocamos las aberturas.');
    expect(query<HTMLInputElement>('[role=switch]').checked).toBe(true);

    button('Reintentar').click();
    fixture.detectChanges();
    const retry = http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/progress');
    expect(retry.request.body.description).toBe('Colocamos las aberturas.');
    expect(retry.request.body.stageUpdate).toEqual({ progressPercent: 40, status: 'IN_PROGRESS' });
    retry.flush({ id: 'e1' });
    fixture.detectChanges();
    expect(text(query('h1'))).toBe('Publicado');
  });

  it('salir con lo escrito o las fotos avisa; mientras suben, dice que la subida se corta; publicado, no', () => {
    render();
    const component = fixture.componentInstance;
    expect(component.leaveWarning()).toBeNull();

    write('Terminamos el encofrado.');
    expect(component.leaveWarning()).toBe('Tenés cambios sin guardar. ¿Salir igual?');

    pick('m1.jpg');
    http.expectOne('/api/developments/d1/media/upload-url');
    expect(component.leaveWarning()).toBe('Se está subiendo 1 foto. Si salís, la subida se corta. ¿Salir igual?');
  });

  it('una vez publicado, salir no avisa', () => {
    render();
    write('Terminamos el encofrado.');
    publish().flush({ id: 'e1' });
    fixture.detectChanges();

    expect(fixture.componentInstance.leaveWarning()).toBeNull();
  });

  describe('Corregir un avance (desde Mis publicaciones)', () => {
    const entry = anEntry({ id: 'e1', developmentId: 'd1', unitId: 'u1', stageId: 's2', entryDate: '2026-10-20', description: 'Hormigonamos la losa.', current: true });

    function renderCorrection(entries = [entry]): void {
      render([house()], STAGES, {}, 'e1');
      http.expectOne('/api/developments/d1/progress').flush(entries);
      fixture.detectChanges();
    }

    it('abre con lo que tenía el avance, sin fotos ni cambio de etapa, y guarda una corrección', () => {
      renderCorrection();

      expect(text(query('h1'))).toBe('Corregir avance');
      expect(query<HTMLTextAreaElement>('#field-text').value).toBe('Hormigonamos la losa.');
      expect(pressed()).toEqual(['Estructura 40%']);
      expect(element.querySelector('app-field-photo-grid')).toBeNull();
      expect(element.querySelector('app-field-stage-update')).toBeNull();
      expect(fixture.componentInstance.leaveWarning()).toBeNull();

      write('Hormigonamos la losa del primer piso.');
      expect(fixture.componentInstance.leaveWarning()).toBe('Tenés cambios sin guardar. ¿Salir igual?');
      button('Guardar corrección').click();
      const request = http.expectOne(req => req.method === 'POST' && req.url === '/api/progress/e1/corrections');
      expect(request.request.body).toEqual({
        entryDate: '2026-10-20', stageId: 's2', description: 'Hormigonamos la losa del primer piso.', unitId: 'u1',
      });
      request.flush(anEntry({ id: 'e2' }));
      fixture.detectChanges();

      expect(text(query('h1'))).toBe('Corregido');
      expect(text(query('.done'))).toContain('La corrección ya está en la historia de Casa 1.');
      expect(fixture.componentInstance.leaveWarning()).toBeNull();
    });

    it('si el avance ya tiene una versión más nueva, lo dice en vez de corregir una vieja', () => {
      renderCorrection([{ ...entry, current: false }]);

      expect(text(element)).toContain('Este avance ya no se puede corregir');
    });
  });

  it('una casa que no es suya lo dice', () => {
    render([house({ unitId: 'otra' })]);

    expect(text(element)).toContain('No estás asignado a esta casa.');
  });
});
