import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideAppIcons } from './icons';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from './page-states.component';

describe('LoadingStateComponent', () => {
  it('se anuncia como estado sin interrumpir', () => {
    TestBed.configureTestingModule({ imports: [LoadingStateComponent] });
    const fixture = TestBed.createComponent(LoadingStateComponent);
    fixture.componentRef.setInput('label', 'Cargando la obra…');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.getAttribute('role')).toBe('status');
    expect(element.getAttribute('aria-live')).toBe('polite');
    expect(element.textContent).toContain('Cargando la obra…');
  });
});

@Component({
  imports: [EmptyStateComponent],
  template: `
    <app-empty-state title="Todavía no hay capturas" message="Subí el primer recorrido." icon="videocam">
      <button type="button">Subir video</button>
    </app-empty-state>
  `,
})
class EmptyStateHost {}

describe('EmptyStateComponent', () => {
  it('dice qué falta y ofrece la acción proyectada', () => {
    TestBed.configureTestingModule({ imports: [EmptyStateHost], providers: [provideAppIcons()] });
    const fixture = TestBed.createComponent(EmptyStateHost);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('h3')?.textContent).toBe('Todavía no hay capturas');
    expect(element.querySelector('p')?.textContent).toBe('Subí el primer recorrido.');
    expect(element.querySelector('.actions button')?.textContent).toBe('Subir video');
  });
});

describe('ErrorStateComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ErrorStateComponent], providers: [provideAppIcons()] });
  });

  it('muestra el motivo y emite el reintento', () => {
    const fixture = TestBed.createComponent(ErrorStateComponent);
    fixture.componentRef.setInput('message', 'Desarrollo no encontrado');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    let retried = 0;
    fixture.componentInstance.retry.subscribe(() => retried++);

    expect(element.getAttribute('role')).toBe('alert');
    expect(element.querySelector('.message')?.textContent).toBe('Desarrollo no encontrado');
    element.querySelector('button')!.click();
    expect(retried).toBe(1);
  });

  it('no ofrece reintentar cuando no tiene sentido', () => {
    const fixture = TestBed.createComponent(ErrorStateComponent);
    fixture.componentRef.setInput('message', 'Esta sección está reservada al responsable del desarrollo');
    fixture.componentRef.setInput('retryable', false);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('button')).toBeNull();
  });
});
