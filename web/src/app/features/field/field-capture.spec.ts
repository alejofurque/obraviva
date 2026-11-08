import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AssignedHouse, Capture3d, CaptureProcessing, MediaAsset } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { aMedia, aStage } from '../../testing/fixtures';
import { FieldCaptureComponent, sectorStateText, sessionMediaProblem, spzProblem } from './field-capture.component';
import { nextPlace, reconstructionMinutes, sessionDayLine, sessionPlaceLine, sessionPlaceProgress, sessionPlaces } from './field-derivations';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const TODAY = todayLocal();

function house(): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Barrio Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: null,
    progressPercent: 40, currentStageName: 'Estructura', currentStagePercent: 40, stageCount: 3, coverUrl: null, coverThumbUrl: null, sectors: ['Frente', 'Living', 'Baño'],
    lastEntryDate: null, lastEntryText: null, lastCaptureDate: null, capturedSectors: 0, capturesInProcess: 0,
  };
}

function processing(zone: string, jobStatus: CaptureProcessing['jobStatus'], failureReason: string | null = null): CaptureProcessing {
  return {
    captureId: 'p-' + zone, unitId: 'u1', stageId: null, captureDate: TODAY, description: null, zone, createdAt: TODAY + 'T10:00:00Z',
    jobStatus, pipelineStep: 'RECONSTRUCTION', stepStartedAt: null, failureReason, mine: true,
  };
}

function available(zone: string): Capture3d {
  return {
    id: 'c-' + zone, developmentId: 'd1', unitId: 'u1', stageId: null, captureDate: TODAY, description: null, zone,
    status: 'AVAILABLE', splatUrl: 'https://s/c.spz',
    transform: { position: { x: 0, y: 0, z: 0 }, rotationDeg: { x: 0, y: 0, z: 0 }, scale: 1 },
  };
}

describe('Grabar recorrido 3D: reglas', () => {
  it('el estado de un sector, con el motivo si falló', () => {
    expect(sectorStateText({ sector: 'Baño', state: 'FAILED', failureReason: 'El video es demasiado oscuro' }))
      .toBe('Falló: El video es demasiado oscuro');
    expect(sectorStateText({ sector: 'Baño', state: 'FAILED' })).toBe('Falló: no se pudo procesar');
    expect(sectorStateText({ sector: 'Baño', state: 'PROCESSING' })).toBe('Reconstruyendo…');
  });

  it('un SPZ tiene que ser .spz y pesar hasta 50 MB', () => {
    expect(spzProblem({ name: 'Living.SPZ', size: 7_000_000 })).toBeNull();
    expect(spzProblem({ name: 'living.ply', size: 7_000_000 })).toContain('tiene que ser un .spz');
    expect(spzProblem({ name: 'living.spz', size: 60 * 1024 * 1024 })).toBe('El archivo pesa 60 MB y el máximo es 50 MB.');
  });
});

describe('FieldCaptureComponent', () => {
  let fixture: ComponentFixture<FieldCaptureComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  afterEach(() => http.verify());

  function render(simulated = true, captures: Capture3d[] = [], inProcess: CaptureProcessing[] = [], media: MediaAsset[] = [],
    front: string | null = 'Frente'): void {
    TestBed.configureTestingModule({
      imports: [FieldCaptureComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ unitId: 'u1' }) } } },
      ],
    });
    fixture = TestBed.createComponent(FieldCaptureComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/reconstruction/mode').flush({ simulated });
    http.expectOne('/api/my-work').flush([house()]);
    http.expectOne('/api/developments/d1/captures').flush(captures);
    http.expectOne('/api/developments/d1/captures/processing').flush(inProcess);
    http.expectOne('/api/units/u1/stages').flush([aStage({ id: 's2', unitId: 'u1', status: 'IN_PROGRESS' })]);
    http.expectOne('/api/developments/d1/media').flush(media);
    http.expectOne('/api/units/u1/sectors/detail')
      .flush(house().sectors.map(name => ({ name, front: name === front })));
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const row = (sector: string) =>
    Array.from(element.querySelectorAll('.sector')).find(item => text(item.querySelector('.sector-name')!) === sector)!;
  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  /** "Recorrido 3D" abre las dos vías: video o archivo de Scaniverse. */
  function open3d(sector: string): void {
    Array.from(row(sector).querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === 'Recorrido 3D')!.click();
    fixture.detectChanges();
  }

  function pick(sector: string, kind: 'video' | 'spz', file: File): void {
    open3d(sector);
    const input = row(sector).querySelectorAll<HTMLInputElement>('.three-d input[type=file]')[kind === 'video' ? 0 : 1];
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  const spzFile = (name = 'living.spz') => new File([new Uint8Array(1000)], name, { type: '' });

  /** Después de terminar, la pantalla vuelve a pedir lo disponible y lo que está en proceso. */
  function flushRefresh(captures: Capture3d[] = [], inProcess: CaptureProcessing[] = []): void {
    http.expectOne('/api/developments/d1/captures').flush(captures);
    http.expectOne('/api/developments/d1/captures/processing').flush(inProcess);
    fixture.detectChanges();
  }

  it('es la sesión del día: cada lugar con lo que tiene y sus acciones, el frente marcado, el próximo resaltado y el anillo', () => {
    render(true, [available('Frente')], [processing('Baño', 'FAILED', 'El video es demasiado oscuro')], [
      aMedia({ id: 'f1', unitId: 'u1', sector: 'Living', takenOn: TODAY, progressEntryId: null }),
      aMedia({ id: 'f2', unitId: 'u1', sector: 'Living', takenOn: TODAY, progressEntryId: null, kind: 'VIDEO' }),
      aMedia({ id: 'g1', unitId: 'u1', sector: 'Living', showcase: true, progressEntryId: null, createdAt: TODAY + 'T12:00:00' }),
      aMedia({ id: 'v1', unitId: 'u1', sector: 'Living', takenOn: '2026-01-02', progressEntryId: null }),
    ], 'Living');

    expect(text(element.querySelector('h1')!)).toBe('Recorrido de hoy');
    expect(text(element.querySelector('.sub')!)).toBe('Casa 1 · ' + sessionDayLine(TODAY));
    expect(text(element.querySelector('.summary')!)).toContain('2/3');
    expect(text(element.querySelector('.summary')!)).toContain('2 de 3 lugares con material');
    // La marca es del lugar marcado como frente, sea cual sea su nombre.
    expect(text(row('Living').querySelector('.front')!)).toBe('Frente');
    expect(row('Frente').querySelector('.front')).toBeNull();
    expect(text(row('Frente').querySelector('.sector-state')!)).toBe('3D listo');
    // Un lugar con material se pliega y se abre al tocarlo.
    expect(text(row('Frente'))).not.toContain('Agregar fotos');
    row('Frente').querySelector<HTMLButtonElement>('button.head')!.click();
    fixture.detectChanges();
    expect(text(row('Frente'))).toContain('Agregar fotos');
    expect(text(row('Frente'))).not.toContain('Recorrido 3D');
    expect(text(row('Baño').querySelector('.sector-state')!)).toBe('3D falló: El video es demasiado oscuro');
    // La galería de venta y lo de otro día no cuentan; la foto y el video de hoy, sí.
    expect(text(row('Living').querySelector('.sector-state')!)).toBe('1 foto · 1 video · sin 3D: se sigue con fotos');
    expect(Array.from(element.querySelectorAll('.sector.next')).map(item => text(item.querySelector('.sector-name')!))).toEqual(['Baño']);
    expect(element.querySelector('#capture-date')).toBeNull();
    button('Otra fecha').click();
    fixture.detectChanges();
    expect(element.querySelector<HTMLInputElement>('#capture-date')!.max).toBe(TODAY);

    open3d('Baño');
    const banio = row('Baño');
    expect(text(banio)).toContain('Subir video');
    expect(text(banio)).toContain('Subir archivo de Scaniverse');
    expect(text(banio.querySelector('.spz .hint')!)).toBe('En Scaniverse: Compartir → Exportar → SPZ');
    const [video, spz] = Array.from(banio.querySelectorAll<HTMLInputElement>('.three-d input[type=file]'));
    expect(video.hasAttribute('capture')).toBe(false);
    expect(video.accept).toBe('video/*');
    expect(spz.hasAttribute('accept')).toBe(false);
    expect(element.querySelector('details.guide')).not.toBeNull();
  });

  it('un SPZ: alta con nombre y tamaño, subida con progreso, confirmación, y "Ver cómo lo ve el comprador"', () => {
    render();
    pick('Living', 'spz', spzFile());

    const create = http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/captures');
    expect(create.request.body).toEqual({
      captureDate: TODAY, description: null, unitId: 'u1', stageId: 's2', zone: 'Living', filename: 'living.spz', fileSize: 1000,
    });
    create.flush({ captureId: 'c9', uploadUrl: 'https://almacenamiento.test/c9', contentType: 'application/octet-stream' });
    const put = http.expectOne('https://almacenamiento.test/c9');
    put.event({ type: HttpEventType.UploadProgress, loaded: 30, total: 100 });
    fixture.detectChanges();
    expect(row('Living').querySelector('[role=progressbar]')!.getAttribute('aria-valuenow')).toBe('30');
    expect(text(row('Living'))).toContain('Subiendo archivo de Scaniverse · 30%');

    put.flush(null);
    http.expectOne(req => req.method === 'POST' && req.url === '/api/captures/c9/confirm').flush(available('Living'));
    flushRefresh([available('Living')]);

    const banner = element.querySelector('.published')!;
    expect(text(banner)).toContain('Living ya está en el recorrido.');
    expect(banner.querySelector('a')!.getAttribute('href'))
      .toBe(`/mi-propiedad/u1/recorrido?obra=d1&desde=hoy&mes=${TODAY.slice(0, 7)}&sector=Living`);
    expect(text(row('Living').querySelector('.sector-state')!)).toBe('3D listo');
  });

  it('si el lugar marcado ya se llama "Frente", no repite la marca', () => {
    render();
    expect(element.querySelector('.front')).toBeNull();
  });

  it('un archivo que no es .spz no se sube', () => {
    render();
    pick('Living', 'spz', spzFile('living.ply'));

    expect(text(element.querySelector('[role=alert]')!)).toContain('tiene que ser un .spz');
    http.expectNone('/api/developments/d1/captures');
  });

  it('si se corta la red, "Reintentar" sigue desde la subida sin volver a crear la captura ni elegir el archivo', () => {
    render();
    pick('Living', 'spz', spzFile());
    http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/captures')
      .flush({ captureId: 'c9', uploadUrl: 'https://almacenamiento.test/c9', contentType: 'application/octet-stream' });
    http.expectOne('https://almacenamiento.test/c9').error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(text(row('Living'))).toContain('Se cortó la conexión');
    button('Reintentar').click();
    fixture.detectChanges();

    http.expectNone(req => req.method === 'POST' && req.url === '/api/developments/d1/captures');
    http.expectOne('https://almacenamiento.test/c9').flush(null);
    http.expectOne('/api/captures/c9/confirm').flush(available('Living'));
    flushRefresh([available('Living')]);
    expect(text(row('Living').querySelector('.sector-state')!)).toBe('3D listo');
  });

  it('si el servidor rechaza el archivo al confirmarlo, lo dice y no ofrece reintentar', () => {
    render();
    pick('Living', 'spz', spzFile());
    http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/captures')
      .flush({ captureId: 'c9', uploadUrl: 'https://almacenamiento.test/c9', contentType: 'application/octet-stream' });
    http.expectOne('https://almacenamiento.test/c9').flush(null);
    http.expectOne('/api/captures/c9/confirm').flush(
      { message: 'El archivo no es un SPZ válido. En Scaniverse: Compartir → Exportar → SPZ.' },
      { status: 400, statusText: 'Bad Request' });
    flushRefresh();

    expect(text(element.querySelector('[role=alert]')!)).toContain('no es un SPZ válido');
    expect(button('Reintentar')).toBeUndefined();
    expect(text(row('Living'))).toContain('Subir archivo de Scaniverse');
  });

  it('un video se elige de la galería, sube con progreso y queda reconstruyéndose', () => {
    render();
    pick('Living', 'video', new File([new Uint8Array(10)], 'living.mp4', { type: 'video/mp4' }));

    const create = http.expectOne('/api/developments/d1/captures/from-video');
    expect(create.request.body).toEqual(expect.objectContaining({ zone: 'Living', videoFilename: 'living.mp4', videoContentType: 'video/mp4' }));
    create.flush({ captureId: 'v1', jobId: 'j1', jobStatus: 'UPLOADED', uploadUrl: 'https://almacenamiento.test/v1', contentType: 'video/mp4' });
    const put = http.expectOne('https://almacenamiento.test/v1');
    put.event({ type: HttpEventType.UploadProgress, loaded: 50, total: 100 });
    fixture.detectChanges();
    expect(text(row('Living'))).toContain('Subiendo video · 50%');
    // Salir mientras sube corta la subida: se avisa y se dice.
    expect(fixture.componentInstance.leaveWarning()).toBe('Se está subiendo el video. Si salís, la subida se corta. ¿Salir igual?');

    put.flush(null);
    http.expectOne('/api/captures/v1/confirm-video').flush({ captureId: 'v1', jobId: 'j1', jobStatus: 'PENDING', uploadUrl: null, contentType: null });
    flushRefresh([], [processing('Living', 'PENDING')]);

    expect(text(row('Living').querySelector('.sector-state')!)).toMatch(/^3D reconstruyendo · \d/);
    expect(text(row('Living').querySelector('.rebuilding')!)).toBe('Reconstruyendo');
    expect(element.querySelector('.published')).toBeNull();
    // Subido: ya no hay nada que cortar.
    expect(fixture.componentInstance.leaveWarning()).toBeNull();
    fixture.destroy();
  });

  it('con el servicio real, el video espera la confirmación del crédito; el SPZ no', () => {
    render(false);
    open3d('Living');
    const [video, spz] = Array.from(row('Living').querySelectorAll<HTMLInputElement>('.three-d input[type=file]'));

    expect(video.disabled).toBe(true);
    expect(spz.disabled).toBe(false);
  });

  function pickMedia(sector: string, ...files: File[]): void {
    const input = row(sector).querySelector<HTMLInputElement>('input[accept="image/*,video/*"]')!;
    Object.defineProperty(input, 'files', { value: files, configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('fotos o video de un lugar: suben con progreso y la fecha de la sesión, sin avance ni galería, y el lugar las cuenta', () => {
    render();
    pickMedia('Frente', new File([new Uint8Array(10)], 'frente.jpg', { type: 'image/jpeg' }));

    const create = http.expectOne(req => req.url === '/api/developments/d1/media/upload-url');
    expect(create.request.body).toEqual({
      filename: 'frente.jpg', contentType: 'image/jpeg', unitId: 'u1', sector: 'Frente', showcase: false, takenOn: TODAY,
    });
    create.flush({ mediaId: 'm9', uploadUrl: 'https://almacenamiento.test/m9', contentType: 'image/jpeg' });
    const put = http.expectOne('https://almacenamiento.test/m9');
    put.event({ type: HttpEventType.UploadProgress, loaded: 70, total: 100 });
    fixture.detectChanges();
    expect(text(row('Frente').querySelector('.media-uploads')!)).toContain('70%');

    put.flush(null);
    http.expectOne('/api/media/m9/confirm').flush(aMedia({ id: 'm9' }));
    http.expectOne('/api/developments/d1/media')
      .flush([aMedia({ id: 'm9', unitId: 'u1', sector: 'Frente', takenOn: TODAY, progressEntryId: null })]);
    fixture.detectChanges();

    expect(row('Frente').querySelector('.media-uploads')).toBeNull();
    expect(text(row('Frente').querySelector('.sector-state')!)).toBe('1 foto · sin 3D: se sigue con fotos');
    expect(text(element.querySelector('.summary')!)).toContain('1 de 3 lugares con material');
  });

  it('si una foto no sube, se reintenta sin volver a elegirla; lo que no es foto ni video no se sube', () => {
    render();
    pickMedia('Frente', new File([new Uint8Array(10)], 'planilla.xlsx', { type: 'application/vnd.ms-excel' }));
    expect(text(element.querySelector('[role=alert]')!)).toContain('no es una foto ni un video');

    pickMedia('Frente', new File([new Uint8Array(10)], 'frente.jpg', { type: 'image/jpeg' }));
    http.expectOne(req => req.url === '/api/developments/d1/media/upload-url')
      .flush({ mediaId: 'm9', uploadUrl: 'https://almacenamiento.test/m9', contentType: 'image/jpeg' });
    http.expectOne('https://almacenamiento.test/m9').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(text(row('Frente').querySelector('.media-uploads')!)).toContain('Se cortó la conexión');

    Array.from(row('Frente').querySelectorAll<HTMLButtonElement>('.media-uploads button')).find(item => text(item) === 'Reintentar')!.click();
    http.expectOne(req => req.url === '/api/developments/d1/media/upload-url')
      .flush({ mediaId: 'm10', uploadUrl: 'https://almacenamiento.test/m10', contentType: 'image/jpeg' });
    http.expectOne('https://almacenamiento.test/m10').flush(null);
    http.expectOne('/api/media/m10/confirm').flush(aMedia({ id: 'm10' }));
    http.expectOne('/api/developments/d1/media').flush([]);
  });
});

describe('Sesión de recorrido: reglas', () => {
  it('un lugar con fotos cuenta como hecho aunque no tenga 3D; la fila dice lo que tiene', () => {
    const places = sessionPlaces(
      [{ sector: 'Frente', state: 'MISSING' }, { sector: 'Living', state: 'DONE' }, { sector: 'Baño', state: 'MISSING' }],
      [aMedia({ unitId: 'u1', sector: 'Frente', takenOn: '2026-10-19', progressEntryId: null }),
        aMedia({ unitId: 'u1', sector: 'Living', takenOn: '2026-10-19', progressEntryId: null }),
        aMedia({ unitId: 'u1', sector: 'Living', takenOn: '2026-10-19', progressEntryId: null })],
      [{ unitId: 'u1', zone: 'Living' }], [], 'u1', '2026-10-19');

    expect(places.map(place => sessionPlaceLine(place))).toEqual(['1 foto · sin 3D: se sigue con fotos', '3D listo · 2 fotos', 'Falta']);
    expect(nextPlace(places)).toBe('Baño');
    expect(places[0].everHad3d).toBe(false);
    expect(sessionPlaceProgress(places)).toEqual({ done: 2, total: 3 });
    expect(sessionMediaProblem({ type: 'video/mp4', size: 600 * 1024 * 1024 })).toContain('500 MB');
    expect(sessionMediaProblem({ type: 'image/jpeg', size: 1000 })).toBeNull();
  });

  it('cuánto lleva reconstruyéndose un lugar, y el día de la sesión', () => {
    const now = new Date('2026-10-27T13:12:00Z');
    const item = { unitId: 'u1', captureDate: '2026-10-27', zone: 'Living', jobStatus: 'PROCESSING' as const, createdAt: '2026-10-27T13:00:00Z' };
    const minutes = reconstructionMinutes([item], 'u1', '2026-10-27', 'Living', now);
    expect(minutes).toBe(12);
    expect(reconstructionMinutes([{ ...item, jobStatus: 'FAILED' }], 'u1', '2026-10-27', 'Living', now)).toBeNull();
    const place = { sector: 'Living', state: 'PROCESSING' as const, photos: 0, videos: 0, everHad3d: true };
    expect(sessionPlaceLine(place, minutes)).toBe('3D reconstruyendo · 12 min');
    expect(sessionPlaceLine(place, 80)).toBe('3D reconstruyendo · 1 h 20 min');
    expect(sessionPlaceLine(place)).toBe('Reconstruyendo…');
    expect(sessionDayLine('2026-10-27')).toBe('martes 27 oct');
  });
});
