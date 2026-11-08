import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  SectorDraft, SectorsEditorComponent, addDraft, draftsFrom, frontSuggestion, markFront, sectorChanges,
} from './sectors-editor.component';

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

const draft = (name: string, front = false, previousName: string | null = name): SectorDraft => ({ name, previousName, front });

describe('Filas del editor de sectores', () => {
  it('cambiar el texto de una fila conserva el nombre anterior: se guarda como renombre', () => {
    const drafts = draftsFrom([{ name: 'Cocina', front: false }]);
    drafts[0].name = '  Cocina   y comedor ';

    expect(sectorChanges(drafts)).toEqual([{ name: 'Cocina y comedor', previousName: 'Cocina', front: false }]);
  });

  it('una fila nueva no tiene nombre anterior, y una vacía no se manda', () => {
    const drafts = addDraft([draft('Frente', true), draft('', false, null)], 'Garage');

    expect(sectorChanges(drafts)).toEqual([
      { name: 'Frente', previousName: 'Frente', front: true },
      { name: 'Garage', previousName: null, front: false },
    ]);
  });

  it('no repite nombres sin importar mayúsculas', () => {
    expect(addDraft([draft('Frente')], 'frente')).toHaveLength(1);
  });

  it('el frente es uno solo: marcar una fila desmarca las demás', () => {
    const drafts = markFront([draft('Frente', true), draft('Cocina')], 1);

    expect(drafts.map(item => item.front)).toEqual([false, true]);
  });

  it('agregar "Frente" ya marcado lo pone primero y desmarca el resto', () => {
    const drafts = addDraft([draft('Cocina', true)], 'Frente', true);

    expect(drafts.map(item => [item.name, item.front])).toEqual([['Frente', true], ['Cocina', false]]);
  });

  it('sin frente sugiere marcar la fila "Frente", o agregarla si no existe; con frente no sugiere nada', () => {
    expect(frontSuggestion([draft('Cocina'), draft('frente')])).toEqual({ kind: 'mark', index: 1 });
    expect(frontSuggestion([draft('Cocina')])).toEqual({ kind: 'add' });
    expect(frontSuggestion([draft('Cocina', true)])).toBeNull();
  });
});

describe('Cambiar sectores', () => {
  let fixture: ComponentFixture<SectorsEditorComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(detail: { name: string; front: boolean }[]): void {
    TestBed.configureTestingModule({
      imports: [SectorsEditorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(SectorsEditorComponent);
    fixture.componentRef.setInput('unitId', 'u1');
    fixture.componentRef.setInput('sectors', detail.map(item => item.name));
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/units/u1/sectors/detail').flush(detail);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label);

  afterEach(() => http.verify());

  it('renombrar una fila avisa que su material pasa al nombre nuevo y lo guarda con previousName', () => {
    render([{ name: 'Frente', front: true }, { name: 'Cocina', front: false }]);
    const input = element.querySelector<HTMLInputElement>('input[aria-label="Nombre del sector 2"]')!;
    input.value = 'Cocina y comedor';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(text(element)).toContain('Antes: Cocina. Sus fotos y recorridos pasan al nombre nuevo.');

    button('Guardar sectores')!.click();
    const save = http.expectOne(request => request.method === 'PUT' && request.url === '/api/units/u1/sectors');
    expect(save.request.body).toEqual({
      sectors: [
        { name: 'Frente', previousName: 'Frente', front: true },
        { name: 'Cocina y comedor', previousName: 'Cocina', front: false },
      ],
    });
    save.flush(['Frente', 'Cocina y comedor']);
  });

  it('sin frente marcado sugiere agregar "Frente" ya marcado', () => {
    render([{ name: 'Dormitorio principal', front: false }, { name: 'Living', front: false }]);
    expect(text(element.querySelector('.front-hint')!)).toContain('Ningún sector está marcado como frente');

    button('Agregar "Frente" y marcarlo')!.click();
    fixture.detectChanges();
    expect(element.querySelector('.front-hint')).toBeNull();
    const radios = Array.from(element.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
    expect(radios.map(radio => radio.checked)).toEqual([true, false, false]);

    button('Guardar sectores')!.click();
    const save = http.expectOne('/api/units/u1/sectors');
    expect(save.request.body.sectors[0]).toEqual({ name: 'Frente', previousName: null, front: true });
    save.flush(['Frente', 'Dormitorio principal', 'Living']);
  });

  it('si ya hay una fila "Frente" sin marcar, sugiere marcarla', () => {
    render([{ name: 'Cocina', front: false }, { name: 'Frente', front: false }]);

    button('Marcar "Frente" como frente')!.click();
    fixture.detectChanges();
    const radios = Array.from(element.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
    expect(radios.map(radio => radio.checked)).toEqual([false, true]);
  });

  it('un 409 por quitar un sector con material muestra el motivo del servidor', () => {
    render([{ name: 'Frente', front: true }, { name: 'Cocina', front: false }]);
    element.querySelector<HTMLButtonElement>('button[aria-label="Quitar Cocina"]')!.click();
    fixture.detectChanges();

    button('Guardar sectores')!.click();
    http.expectOne('/api/units/u1/sectors').flush(
      { message: 'Cocina tiene 3 fotos y 1 recorridos: renombralo o movelos antes de quitarlo.' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(text(element.querySelector('[role="alert"]')!)).toContain('Cocina tiene 3 fotos y 1 recorridos');
  });
});
