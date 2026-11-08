import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { LeaveConfirmation, LeaveGuarded, UNSAVED_CHANGES, leaveGuard, warnOnUnload } from './leave-guard';

const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

@Component({ selector: 'app-host', template: '' })
class HostComponent {
  readonly warning = signal<string | null>(null);
  constructor() {
    warnOnUnload(() => this.warning());
  }
}

describe('Aviso al salir con cambios sin guardar', () => {
  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach(container => (container.innerHTML = '')));

  function runGuard(component: LeaveGuarded): boolean | Observable<boolean> {
    return TestBed.runInInjectionContext(() => leaveGuard(
      component, {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot, {} as RouterStateSnapshot)) as boolean | Observable<boolean>;
  }

  function dialogButton(label: string): HTMLButtonElement {
    TestBed.tick();
    const found = Array.from(document.querySelectorAll<HTMLButtonElement>('app-leave-dialog button')).find(button => text(button) === label);
    if (!found) {
      throw new Error(`No está el botón "${label}"`);
    }
    return found;
  }

  it('sin nada que perder, sale sin preguntar', async () => {
    const result = runGuard({ leaveWarning: () => null });
    expect(isObservable(result) ? await firstValueFrom(result) : result).toBe(true);
    TestBed.tick();
    expect(document.querySelector('app-leave-dialog')).toBeNull();
  });

  it('con cambios, pregunta con el diálogo de la app; "Quedarme" no sale', async () => {
    const result = runGuard({ leaveWarning: () => UNSAVED_CHANGES }) as Observable<boolean>;
    const answer = firstValueFrom(result);

    TestBed.tick();
    expect(text(document.querySelector('app-leave-dialog .message'))).toBe('Tenés cambios sin guardar. ¿Salir igual?');
    dialogButton('Quedarme').click();
    expect(await answer).toBe(false);
  });

  it('"Salir igual" sale', async () => {
    const answer = firstValueFrom(TestBed.inject(LeaveConfirmation).confirm('Se está subiendo el video. Si salís, la subida se corta. ¿Salir igual?'));

    TestBed.tick();
    expect(text(document.querySelector('app-leave-dialog .message'))).toContain('la subida se corta');
    dialogButton('Salir igual').click();
    expect(await answer).toBe(true);
  });

  it('cerrar o recargar la pestaña pide confirmación sólo si hay algo que perder', () => {
    const host = TestBed.createComponent(HostComponent);
    const unload = () => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };

    expect(unload()).toBe(false);
    host.componentInstance.warning.set(UNSAVED_CHANGES);
    expect(unload()).toBe(true);

    host.destroy();
    expect(unload()).toBe(false);
  });
});
