import { Directive, ElementRef, forwardRef, inject } from '@angular/core';
import { AbstractControl, ControlValueAccessor, NG_VALIDATORS, NG_VALUE_ACCESSOR, ValidationErrors, Validator } from '@angular/forms';
import { formatAmount, parseAmount } from './amounts';

/**
 * Un número grande (un precio, una superficie) escrito como en Argentina: "42.000" o
 * "185.000,50". El campo muestra el separador de miles y el formulario recibe el número de
 * siempre, así las pantallas no cambian su lógica. Lo que no se puede leer ("42000.5" a la
 * inglesa) no se adivina: el control queda inválido con `amount` y el texto, como se escribió.
 * Se vuelve a formatear al salir del campo, no mientras se escribe, para no mover el cursor.
 */
@Directive({
  selector: 'input[appAmount]',
  host: { type: 'text', inputmode: 'decimal', autocomplete: 'off', '(input)': 'read()', '(blur)': 'leave()' },
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => AmountInputDirective), multi: true },
    { provide: NG_VALIDATORS, useExisting: forwardRef(() => AmountInputDirective), multi: true },
  ],
})
export class AmountInputDirective implements ControlValueAccessor, Validator {
  private readonly input = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;
  private unreadable = false;
  private onChange: (value: number | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: number | null | undefined): void {
    this.unreadable = false;
    this.input.value = value === null || value === undefined ? '' : formatAmount(value);
  }

  registerOnChange(fn: (value: number | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.input.disabled = disabled;
  }

  validate(_control: AbstractControl): ValidationErrors | null {
    return this.unreadable ? { amount: true } : null;
  }

  protected read(): void {
    const value = parseAmount(this.input.value);
    this.unreadable = value === 'invalid';
    this.onChange(value === 'invalid' ? null : value);
  }

  protected leave(): void {
    this.onTouched();
    const value = parseAmount(this.input.value);
    if (typeof value === 'number') {
      this.input.value = formatAmount(value);
    }
  }
}
