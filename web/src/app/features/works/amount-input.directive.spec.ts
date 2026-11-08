import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { AmountInputDirective } from './amount-input.directive';

@Component({
  imports: [ReactiveFormsModule, AmountInputDirective],
  template: `<input appAmount [formControl]="control" />`,
})
class AmountHostComponent {
  readonly control = new FormControl<number | null>(185000);
}

describe('Precio y superficie con separador de miles', () => {
  function render() {
    TestBed.configureTestingModule({ imports: [AmountHostComponent] });
    const fixture = TestBed.createComponent(AmountHostComponent);
    fixture.detectChanges();
    const input = (fixture.nativeElement as HTMLElement).querySelector('input')!;
    return { fixture, input, control: fixture.componentInstance.control };
  }

  function type(input: HTMLInputElement, text: string): void {
    input.value = text;
    input.dispatchEvent(new Event('input'));
  }

  it('muestra el número con punto de miles y es un campo de texto con teclado numérico', () => {
    const { input } = render();
    expect(input.value).toBe('185.000');
    expect(input.type).toBe('text');
    expect(input.getAttribute('inputmode')).toBe('decimal');
  });

  it('el formulario recibe el número, se escriba con o sin puntos', () => {
    const { input, control } = render();
    type(input, '42000');
    expect(control.value).toBe(42000);
    type(input, '42.000,5');
    expect(control.value).toBe(42000.5);
    type(input, '');
    expect(control.value).toBeNull();
    expect(control.valid).toBe(true);
  });

  it('al salir del campo lo vuelve a escribir con el separador', () => {
    const { input } = render();
    type(input, '42000');
    input.dispatchEvent(new Event('blur'));
    expect(input.value).toBe('42.000');
  });

  it('lo que no se puede leer no se adivina: queda inválido y con el texto tal cual', () => {
    const { input, control } = render();
    type(input, '42000.5');
    expect(control.hasError('amount')).toBe(true);
    input.dispatchEvent(new Event('blur'));
    expect(input.value).toBe('42000.5');
  });
});
