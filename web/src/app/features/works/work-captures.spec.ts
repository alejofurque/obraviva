import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Capture3d, MediaAsset } from '../../core/api/api.models';
import { aDevelopment, aMedia } from '../../testing/fixtures';
import { HouseStore } from './house.store';
import { WorkCapturesComponent } from './work-captures.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function capture(id: string, captureDate: string, zone: string | null): Capture3d {
  return {
    id, developmentId: 'd1', unitId: 'u1', stageId: null, captureDate, description: null, zone, status: 'AVAILABLE',
    transform: { position: { x: 0, y: 0, z: 0 }, rotationDeg: { x: 0, y: 0, z: 0 }, scale: 1 }, splatUrl: 'https://s/' + id,
  };
}

describe('Lugares y 3D de una casa', () => {
  let fixture: ComponentFixture<WorkCapturesComponent>;
  let element: HTMLElement;
  let http: HttpTestingController;

  function render(media: MediaAsset[] = [], owner = true, credits: { simulated: boolean; credits: number | null } = { simulated: false, credits: 9 }): void {
    TestBed.configureTestingModule({
      imports: [WorkCapturesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => owner), reload: () => {} },
        },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), reload: () => {} } },
      ],
    });
    fixture = TestBed.createComponent(WorkCapturesComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/reconstruction/mode').flush({ simulated: credits.simulated });
    if (owner) {
      http.expectOne('/api/reconstruction/credits').flush(credits);
    } else {
      http.expectNone('/api/reconstruction/credits');
    }
    http.expectOne('/api/developments/d1/captures').flush([
      capture('c1', '2026-07-05', 'Cocina'), capture('c2', '2026-10-05', 'Cocina'), capture('c3', '2026-10-05', 'Living'),
      capture('c4', '2026-06-01', null),
    ]);
    http.expectOne('/api/developments/d1/captures/processing').flush([]);
    http.expectOne('/api/units/u1/stages').flush([]);
    http.expectOne('/api/units/u1/sectors/detail').flush([
      { name: 'Living', front: false }, { name: 'Cocina', front: false }, { name: 'Baño', front: true },
    ]);
    http.expectOne('/api/developments/d1/media').flush(media);
    http.expectOne('/api/developments/d1/progress').flush([]);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const cell = (place: string, month: string) => element.querySelector<HTMLButtonElement>(`app-place-grid button[aria-label^="${place}, ${month}"]`)!;

  it('el encabezado ofrece cambiar lugares, alinear en el visor y el recorrido de hoy; el video suelto arranca plegado', () => {
    render();
    const header = element.querySelector('.intro')!;

    expect(text(header.querySelector('h2')!)).toBe('Lugares y 3D');
    expect(text(header)).toContain('Cambiar lugares');
    expect(text(header)).toContain('Alinear en el visor');
    expect(header.querySelector('a.ov-btn--primary')?.getAttribute('href')).toBe('/hoy/u1/recorrido');
    expect(text(header)).toContain('Cómo filmar para que el 3D salga bien');

    const upload = element.querySelector<HTMLDetailsElement>('details.upload')!;
    expect(upload.open).toBe(false);
    expect(upload.querySelector('.pill-alert')?.textContent?.trim()).toBe('Consume créditos');
    expect(text(upload)).toContain('Entiendo que este video consume un crédito');
    expect(element.querySelector('[class*="danger"]')).toBeNull();
  });

  it('la grilla tiene un lugar por fila, en el orden de la casa, con el frente marcado y General al final', () => {
    render();
    const rows = Array.from(element.querySelectorAll('app-place-grid tbody tr'));

    expect(rows.map(row => text(row.querySelector('.name')!))).toEqual(['Living', 'Cocina', 'Baño Frente', 'General']);
    expect(Array.from(element.querySelectorAll('app-place-grid thead th.month')).map(text)).toEqual(['Jun 26', 'Jul 26', 'Ago 26', 'Sep 26', 'Oct 26']);
    expect(text(rows[1].querySelector('.meta')!)).toBe('2 recorridos');
    expect(text(rows[2].querySelector('.meta')!)).toBe('Sin material todavía');
    expect(cell('Cocina', 'julio 2026').getAttribute('aria-label')).toBe('Cocina, julio 2026: 1 recorrido 3D');
    expect(cell('Baño', 'julio 2026').classList).toContain('empty');
  });

  it('abre en la celda más nueva; tocar otra muestra su detalle, y "Editar datos del recorrido" guarda', () => {
    render();
    expect(text(element.querySelector('app-place-detail h3')!)).toBe('Living · octubre 2026');

    cell('Cocina', 'julio 2026').click();
    fixture.detectChanges();
    const detail = element.querySelector('app-place-detail')!;
    expect(text(detail.querySelector('h3')!)).toBe('Cocina · julio 2026');
    expect(text(detail.querySelector('.tour')!)).toContain('Recorrido 3D · 5 jul');
    expect(detail.querySelector('a')?.getAttribute('href')).toBe('/obras/d1/casas/u1/recorridos/visor');

    Array.from(detail.querySelectorAll<HTMLButtonElement>('button')).find(button => text(button) === 'Editar datos del recorrido')!.click();
    fixture.detectChanges();
    const description = element.querySelector<HTMLInputElement>('#edit-description-c1')!;
    description.value = 'Cocina con la mesada';
    description.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('app-place-detail form')!.dispatchEvent(new Event('submit'));
    const patch = http.expectOne(request => request.method === 'PATCH' && request.url === '/api/captures/c1');
    expect(patch.request.body).toEqual({ captureDate: '2026-07-05', zone: 'Cocina', stageId: null, description: 'Cocina con la mesada' });
  });

  it('las fotos de una celda se pueden pasar a otro lugar y borrar', () => {
    const photo = aMedia({ id: 'm1', unitId: 'u1', sector: 'Baño', progressEntryId: null, progressRootId: null, takenOn: '2026-10-14' });
    render([photo]);
    expect(cell('Baño', 'octubre 2026').getAttribute('aria-label')).toBe('Baño, octubre 2026: 1 foto o video');

    cell('Baño', 'octubre 2026').click();
    fixture.detectChanges();
    const chip = element.querySelector<HTMLSelectElement>('app-place-detail app-sector-chip select')!;
    expect(chip.value).toBe('Baño');
    chip.value = 'Cocina';
    chip.dispatchEvent(new Event('change'));
    const patch = http.expectOne(request => request.method === 'PATCH' && request.url === '/api/media/m1/sector');
    expect(patch.request.body).toEqual({ sector: 'Cocina' });
    patch.flush({ ...photo, sector: 'Cocina' });
    fixture.detectChanges();
    expect(cell('Cocina', 'octubre 2026').getAttribute('aria-label')).toBe('Cocina, octubre 2026: 1 recorrido 3D, 1 foto o video');

    cell('Cocina', 'octubre 2026').click();
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    Array.from(element.querySelectorAll<HTMLButtonElement>('app-place-detail button')).find(button => text(button) === 'Borrar')!.click();
    http.expectOne(request => request.method === 'DELETE' && request.url === '/api/media/m1').flush(null);
    fixture.detectChanges();
    expect(cell('Cocina', 'octubre 2026').getAttribute('aria-label')).toBe('Cocina, octubre 2026: 1 recorrido 3D');
    http.verify();
  });

  it('el dueño ve los créditos que quedan; en modo simulado, el texto en vez del número', () => {
    render();
    expect(text(element.querySelector('app-reconstruction-credits')!)).toContain('9 créditos disponibles');
    TestBed.resetTestingModule();

    render([], true, { simulated: true, credits: null });
    const card = element.querySelector('app-reconstruction-credits')!;
    expect(text(card)).toContain('Modo simulado: los videos no consumen créditos.');
    expect(card.querySelector('.value')).toBeNull();
  });

  it('el encargado no pide ni ve los créditos', () => {
    render([], false);
    expect(element.querySelector('app-reconstruction-credits')).toBeNull();
    expect(text(element.querySelector('.intro')!)).not.toContain('Cambiar lugares');
  });
});
