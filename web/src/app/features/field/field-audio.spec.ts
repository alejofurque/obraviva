import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { AssignedHouse, ExtractionDetail, ExtractionSummary } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { STANDARD_SECTORS, aStage } from '../../testing/fixtures';
import { approvalEntries, audioContentType, audioStepLabel, reviewCards } from './audio-review';
import { FieldAudioComponent, canRecordAudio, elapsedLabel } from './field-audio.component';
import { FieldRecorderComponent, levelOf } from './field-recorder.component';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function house(): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Barrio Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: null,
    progressPercent: 40, currentStageName: 'Estructura', currentStagePercent: 40, stageCount: 2, coverUrl: null, coverThumbUrl: null, sectors: STANDARD_SECTORS,
    lastEntryDate: null, lastEntryText: null, lastCaptureDate: null, capturedSectors: 0, capturesInProcess: 0,
  };
}

function summary(overrides: Partial<ExtractionSummary> = {}): ExtractionSummary {
  return {
    id: 'x1', developmentId: 'd1', reviewStatus: 'PENDING', transcriptPreview: null, proposedElements: 0,
    createdAt: '2026-10-14T10:00:00Z', unitId: 'u1', jobStatus: 'PENDING', pipelineStep: 'TRANSCRIBE',
    reviewNotes: null, mine: true, ...overrides,
  };
}

function detail(): ExtractionDetail {
  return {
    id: 'x1', developmentId: 'd1', reviewStatus: 'READY_FOR_REVIEW', transcript: 'Arrancamos la estructura y faltan caños.',
    proposal: {
      stageProposals: [
        { stageId: 's2', proposedStatus: 'IN_PROGRESS', rationale: 'arrancamos la estructura', confidence: 'HIGH' },
        { stageId: 's3', proposedStatus: 'DELAYED', rationale: 'Se demora la mampostería por lluvia', confidence: 'LOW' },
      ],
      progressNotes: [{ stageId: 's2', description: 'Arrancó la estructura', confidence: 'HIGH' }],
      supplyAlerts: [{ description: 'Faltan caños corrugados', confidence: 'HIGH' }],
    },
    promptVersion: 'v1', modelId: 'mock', audioUrl: null, createdAt: '2026-10-14T10:00:00Z',
    reviewedBy: null, reviewedAt: null, reviewNotes: null, unitId: 'u1',
  };
}

describe('Enviar audio: reglas', () => {
  it('una novedad y el cambio de estado de su etapa van juntos; un cambio sin novedad lleva su fundamento como texto', () => {
    const cards = reviewCards(detail().proposal);

    expect(cards.map(card => [card.stageId, card.description, card.status])).toEqual([
      ['s2', 'Arrancó la estructura', 'IN_PROGRESS'],
      ['s3', 'Se demora la mampostería por lluvia', 'DELAYED'],
    ]);
    expect(cards.every(card => card.acceptStatus && card.percent === null)).toBe(true);
  });

  it('volver a pendiente no se propone, y lo aprobado lleva el porcentaje sólo si lo puso la persona', () => {
    const cards = reviewCards({
      stageProposals: [{ stageId: 's2', proposedStatus: 'PENDING', rationale: 'x', confidence: 'LOW' }],
      progressNotes: [{ stageId: null, description: 'Llegó el hierro', confidence: 'HIGH' }],
      supplyAlerts: [],
    });
    expect(cards).toHaveLength(1);

    const reviewed = reviewCards(detail().proposal);
    reviewed[0].percent = 25;
    reviewed[1].acceptStatus = false;
    expect(approvalEntries(reviewed, { [reviewed[0].key]: ['m1'] })).toEqual([
      { stageId: 's2', description: 'Arrancó la estructura', photoIds: ['m1'], stageUpdate: { status: 'IN_PROGRESS', progressPercent: 25 } },
      { stageId: 's3', description: 'Se demora la mampostería por lluvia', photoIds: [], stageUpdate: null },
    ]);
    reviewed[0].status = 'COMPLETED';
    expect(approvalEntries(reviewed)[0].stageUpdate).toEqual({ status: 'COMPLETED', progressPercent: 100 });
  });

  it('el lugar de cada foto viaja por su id, sólo en la tarjeta que tiene fotos con lugar', () => {
    const reviewed = reviewCards(detail().proposal);
    const entries = approvalEntries(reviewed, { [reviewed[0].key]: ['m1', 'm2'] }, { [reviewed[0].key]: { m1: 'Frente' } });

    expect(entries[0].photoSectors).toEqual({ m1: 'Frente' });
    expect('photoSectors' in entries[1]).toBe(false);
  });

  it('un audio de WhatsApp sin tipo se manda como audio/ogg', () => {
    expect(audioContentType({ name: 'PTT-20261014-WA0003.opus', type: '' })).toBe('audio/ogg');
    expect(audioContentType({ name: 'nota.m4a', type: '' })).toBe('audio/mp4');
    expect(audioContentType({ name: 'grabacion.webm', type: 'audio/webm;codecs=opus' })).toBe('audio/webm');
    expect(audioContentType({ name: 'planilla.xlsx', type: '' })).toBe('application/octet-stream');
  });

  it('los pasos, el reloj y cuándo se puede grabar', () => {
    expect(audioStepLabel({ reviewStatus: 'PENDING', pipelineStep: 'TRANSCRIBE', jobStatus: 'PROCESSING' })).toBe('Transcribiendo…');
    expect(audioStepLabel({ reviewStatus: 'PENDING', pipelineStep: 'INTERPRET', jobStatus: 'PENDING' })).toBe('Ordenando…');
    expect(audioStepLabel({ reviewStatus: 'READY_FOR_REVIEW', pipelineStep: 'AWAIT_REVIEW', jobStatus: 'NEEDS_REVIEW' })).toBe('Listo para revisar');
    expect(elapsedLabel(65.4)).toBe('1:05');
    // Por la red local (http://IP) el navegador no da el micrófono: no hay botón de grabar.
    expect(canRecordAudio({ isSecureContext: false, MediaRecorder: class {}, navigator: { mediaDevices: { getUserMedia: () => null } } as never })).toBe(false);
    expect(canRecordAudio({ isSecureContext: true, MediaRecorder: class {}, navigator: { mediaDevices: { getUserMedia: () => null } } as never })).toBe(true);
  });
});

describe('Grabar: la onda con el nivel del micrófono', () => {
  it('el nivel es 0 en silencio y sube con la voz, sin pasar de 1', () => {
    expect(levelOf(new Uint8Array(512).fill(128))).toBe(0);
    const voice = Uint8Array.from({ length: 512 }, (_, i) => (i % 2 === 0 ? 148 : 108));
    expect(levelOf(voice)).toBeCloseTo(0.625, 3);
    expect(levelOf(Uint8Array.from({ length: 512 }, (_, i) => (i % 2 === 0 ? 255 : 0)))).toBe(1);
    expect(levelOf(new Uint8Array(0))).toBe(0);
  });

  it('un botón redondo que dice qué hace; sin micrófono abierto la onda queda quieta', () => {
    TestBed.configureTestingModule({ imports: [FieldRecorderComponent] });
    const recorder = TestBed.createComponent(FieldRecorderComponent);
    recorder.detectChanges();
    const host = recorder.nativeElement as HTMLElement;
    const record = host.querySelector<HTMLButtonElement>('button.record')!;
    expect(record.getAttribute('aria-label')).toBe('Grabar');
    expect(text(host.querySelector('.hint')!)).toBe('Tocá para grabar');
    expect(host.querySelectorAll('.wave span')).toHaveLength(24);
    expect(Array.from(host.querySelectorAll<HTMLElement>('.wave span')).every(bar => bar.style.height === '6px')).toBe(true);

    recorder.componentRef.setInput('recording', true);
    recorder.componentRef.setInput('seconds', 48);
    recorder.detectChanges();
    expect(record.getAttribute('aria-label')).toBe('Terminar de grabar');
    expect(text(host.querySelector('.clock')!)).toBe('0:48');
    expect(text(host.querySelector('.hint')!)).toBe('Tocá para terminar');
  });
});

describe('FieldAudioComponent', () => {
  let fixture: ComponentFixture<FieldAudioComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  afterEach(() => http.verify());

  function render(extractionId: string | null): void {
    TestBed.configureTestingModule({
      imports: [FieldAudioComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Carlos Medina', email: 'carlos@obraviva.test' }).asReadonly() } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(extractionId ? { unitId: 'u1', extractionId } : { unitId: 'u1' }) } } },
      ],
    });
    fixture = TestBed.createComponent(FieldAudioComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/my-work').flush([house()]);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  function pick(file: File): void {
    const input = element.querySelector<HTMLInputElement>('input[type=file]')!;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('sin micrófono (red local) ofrece elegir el audio; uno de WhatsApp sube con progreso y pasa a la espera', () => {
    render(null);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    expect(button('Grabar')).toBeUndefined();
    expect(text(element)).toContain('Grabalo con la app de notas de voz o mandátelo por WhatsApp');
    pick(new File([new Uint8Array(100)], 'PTT-20261014-WA0003.opus', { type: '' }));
    button('Enviar').click();
    fixture.detectChanges();

    const create = http.expectOne('/api/developments/d1/audio-extractions');
    expect(create.request.body).toEqual({ audioFilename: 'PTT-20261014-WA0003.opus', audioContentType: 'audio/ogg', unitId: 'u1' });
    create.flush({ extractionId: 'x1', jobId: 'j1', reviewStatus: 'PENDING', uploadUrl: 'https://almacenamiento.test/x1', contentType: 'audio/ogg' });
    const put = http.expectOne('https://almacenamiento.test/x1');
    put.event({ type: HttpEventType.UploadProgress, loaded: 60, total: 100 });
    fixture.detectChanges();
    expect(text(element)).toContain('Subiendo el audio · 60%');

    put.flush(null);
    http.expectOne('/api/audio-extractions/x1/confirm-audio').flush({ extractionId: 'x1' });
    expect(navigate).toHaveBeenCalledWith(['/hoy', 'u1', 'audio', 'x1'], { replaceUrl: true, state: { audioSeconds: null } });
  });

  it('si se corta la red, "Reintentar" sigue sin volver a crear ni elegir el audio', () => {
    render(null);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    pick(new File([new Uint8Array(100)], 'parte.ogg', { type: 'audio/ogg' }));
    button('Enviar').click();
    http.expectOne('/api/developments/d1/audio-extractions')
      .flush({ extractionId: 'x1', jobId: 'j1', reviewStatus: 'PENDING', uploadUrl: 'https://almacenamiento.test/x1', contentType: 'audio/ogg' });
    http.expectOne('https://almacenamiento.test/x1').error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(text(element)).toContain('Se cortó la conexión');
    button('Reintentar').click();
    http.expectNone('/api/developments/d1/audio-extractions');
    http.expectOne('https://almacenamiento.test/x1').flush(null);
    http.expectOne('/api/audio-extractions/x1/confirm-audio').flush({ extractionId: 'x1' });
  });

  it('un archivo que no es audio no se manda', () => {
    render(null);
    pick(new File([new Uint8Array(10)], 'planilla.xlsx', { type: '' }));

    expect(text(element.querySelector('[role=alert]')!)).toContain('tiene que ser un audio');
    expect(button('Enviar')).toBeUndefined();
  });

  it('mientras se procesa muestra el paso; listo, la revisión en tarjetas con la transcripción plegada y las alertas', () => {
    vi.useFakeTimers();
    render('x1');
    http.expectOne('/api/developments/d1/audio-extractions').flush([summary({ pipelineStep: 'INTERPRET' })]);
    fixture.detectChanges();

    expect(text(element.querySelector('.sub')!)).toBe('Casa 1 · Procesando');
    expect(text(element.querySelector('.steps li.done-step')!)).toBe('Audio subido');
    expect(text(element.querySelector('.steps li.current')!)).toBe('Ordenando las novedades');
    expect(text(element)).toContain('queda en Hoy');

    vi.advanceTimersByTime(4000);
    http.expectOne('/api/developments/d1/audio-extractions')
      .flush([summary({ reviewStatus: 'READY_FOR_REVIEW', jobStatus: 'NEEDS_REVIEW', pipelineStep: 'AWAIT_REVIEW' })]);
    http.expectOne('/api/audio-extractions/x1').flush(detail());
    http.expectOne('/api/units/u1/stages').flush([
      aStage({ id: 's2', name: 'Estructura', orderIndex: 2, progressPercent: 40, unitId: 'u1' }),
      aStage({ id: 's3', name: 'Mampostería', orderIndex: 3, progressPercent: 0, status: 'PENDING', unitId: 'u1' }),
    ]);
    fixture.detectChanges();
    vi.useRealTimers();

    expect(element.querySelector('details.transcript')!.hasAttribute('open')).toBe(false);
    const cards = Array.from(element.querySelectorAll('.card'));
    expect(cards).toHaveLength(2);
    expect(cards[0].querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Arrancó la estructura');
    expect(text(cards[0])).toContain('Actualizar la etapa a en curso');
    expect(cards[0].querySelector<HTMLInputElement>('input[type=number]')!.placeholder).toBe('Sin cambiar (40%)');
    expect(text(element.querySelector('.sub')!)).toBe('Casa 1 · Revisar y publicar');
    expect(text(element.querySelector('.alerts')!)).toContain('Faltan caños corrugados');
    expect(text(element.querySelector('.alerts')!)).toContain('No se publica. Le llega al responsable en Novedades.');
    expect(text(cards[0].querySelector('.remove-card')!)).toBe('Descartar');
  });

  function openReview(): void {
    render('x1');
    http.expectOne('/api/developments/d1/audio-extractions')
      .flush([summary({ reviewStatus: 'READY_FOR_REVIEW', jobStatus: 'NEEDS_REVIEW', pipelineStep: 'AWAIT_REVIEW' })]);
    http.expectOne('/api/audio-extractions/x1').flush(detail());
    http.expectOne('/api/units/u1/stages').flush([
      aStage({ id: 's2', name: 'Estructura', orderIndex: 2, progressPercent: 40, unitId: 'u1' }),
      aStage({ id: 's3', name: 'Mampostería', orderIndex: 3, progressPercent: 0, status: 'PENDING', unitId: 'u1' }),
    ]);
    fixture.detectChanges();
  }

  it('publica cada tarjeta como un avance, con el porcentaje que puso la persona, y confirma con "Ver cómo lo ve el comprador"', () => {
    openReview();
    const [first, second] = Array.from(element.querySelectorAll('.card'));
    Array.from(first.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === '+5')!.click();
    second.querySelector<HTMLInputElement>('[role=switch]')!.click();
    fixture.detectChanges();
    button('Publicar 2 avances').click();

    const approve = http.expectOne('/api/audio-extractions/x1/approve');
    expect(approve.request.body).toEqual({
      entryDate: todayLocal(),
      entries: [
        { stageId: 's2', description: 'Arrancó la estructura', photoIds: [], stageUpdate: { status: 'IN_PROGRESS', progressPercent: 45 } },
        { stageId: 's3', description: 'Se demora la mampostería por lluvia', photoIds: [], stageUpdate: null },
      ],
    });
    approve.flush({ extractionId: 'x1', reviewStatus: 'APPROVED', publishedEntries: 2, updatedStages: 1 });
    fixture.detectChanges();

    expect(text(element.querySelector('h1')!)).toBe('Publicado');
    expect(element.querySelector('.done a')!.getAttribute('href')).toBe('/mi-propiedad/u1?obra=d1&desde=hoy');
  });

  it('las fotos de una tarjeta llevan el chip de lugar, precargado con el último elegido en la casa', () => {
    try {
      localStorage.setItem('obraviva.field.sector.me.u1', 'Frente');
    } catch {
      // Sin almacenamiento el chip arranca vacío: el test elige a mano igual.
    }
    openReview();
    const first = element.querySelector('.card')!;
    const input = first.querySelector<HTMLInputElement>('app-pending-photos input[type=file]')!;
    Object.defineProperty(input, 'files', { value: [new File([new Uint8Array(10)], 'frente.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));
    http.expectOne(req => req.url === '/api/developments/d1/media/upload-url' && req.body.unitId === 'u1')
      .flush({ mediaId: 'm9', uploadUrl: 'https://almacenamiento.test/m9', contentType: 'image/jpeg' });
    http.expectOne('https://almacenamiento.test/m9').flush(null);
    fixture.detectChanges();

    const chip = first.querySelector<HTMLSelectElement>('app-pending-photos app-sector-chip select')!;
    expect(Array.from(chip.options).map(option => option.text)).toEqual(['Sin lugar', ...STANDARD_SECTORS]);
    chip.value = 'Living y cocina';
    chip.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    button('Publicar 2 avances').click();

    const approve = http.expectOne('/api/audio-extractions/x1/approve');
    expect(approve.request.body.entries[0].photoIds).toEqual(['m9']);
    expect(approve.request.body.entries[0].photoSectors).toEqual({ m9: 'Living y cocina' });
    expect('photoSectors' in approve.request.body.entries[1]).toBe(false);
    approve.flush({ extractionId: 'x1', reviewStatus: 'APPROVED', publishedEntries: 2, updatedStages: 1 });
    try {
      expect(localStorage.getItem('obraviva.field.sector.me.u1')).toBe('Living y cocina');
      localStorage.removeItem('obraviva.field.sector.me.u1');
    } catch {
      // Nada que limpiar.
    }
  });

  it('el chip arranca con el último lugar elegido en la casa', () => {
    try {
      localStorage.setItem('obraviva.field.sector.me.u1', 'Frente');
    } catch {
      return;
    }
    openReview();
    const input = element.querySelector<HTMLInputElement>('.card app-pending-photos input[type=file]')!;
    Object.defineProperty(input, 'files', { value: [new File([new Uint8Array(10)], 'frente.jpg', { type: 'image/jpeg' })] });
    input.dispatchEvent(new Event('change'));
    http.expectOne(req => req.url === '/api/developments/d1/media/upload-url')
      .flush({ mediaId: 'm9', uploadUrl: 'https://almacenamiento.test/m9', contentType: 'image/jpeg' });
    http.expectOne('https://almacenamiento.test/m9').flush(null);
    fixture.detectChanges();

    expect(element.querySelector<HTMLSelectElement>('.card app-sector-chip select')!.value).toBe('Frente');
    localStorage.removeItem('obraviva.field.sector.me.u1');
  });

  it('si el servidor rechaza, no se publicó nada y lo corregido sigue ahí; sin tarjetas, propone descartar', () => {
    openReview();
    const area = element.querySelector<HTMLTextAreaElement>('.card textarea')!;
    area.value = 'Arrancó la estructura del primer piso';
    area.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button('Publicar 2 avances').click();
    http.expectOne('/api/audio-extractions/x1/approve')
      .flush({ message: 'La etapa no pertenece a esta casa' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(text(element.querySelector('.publish-bar [role=alert]')!)).toContain('La etapa no pertenece a esta casa');
    expect(element.querySelector<HTMLTextAreaElement>('.card textarea')!.value).toBe('Arrancó la estructura del primer piso');

    Array.from(element.querySelectorAll<HTMLButtonElement>('.remove-card')).forEach(item => item.click());
    fixture.detectChanges();
    expect(button('Publicar').disabled).toBe(true);
    expect(text(element.querySelector('.publish-bar')!)).toContain('descartalo');
  });

  it('"Descartar audio" pide confirmación y vuelve a Hoy', () => {
    openReview();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);

    button('Descartar audio').click();
    http.expectOne('/api/audio-extractions/x1/reject').flush(detail());

    expect(navigate).toHaveBeenCalledWith(['/hoy'], { replaceUrl: true });
    confirm.mockRestore();
  });

  it('si falló, dice por qué y ofrece enviar otro', () => {
    render('x1');
    http.expectOne('/api/developments/d1/audio-extractions').flush([summary({
      reviewStatus: 'FAILED', jobStatus: 'FAILED', reviewNotes: 'No se pudo procesar el audio después de varios intentos.',
    })]);
    fixture.detectChanges();

    expect(text(element)).toContain('después de varios intentos');
    expect(element.querySelector('.notice a')!.getAttribute('href')).toBe('/hoy/u1/audio');
  });
});
