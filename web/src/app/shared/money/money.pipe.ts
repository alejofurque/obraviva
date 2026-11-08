import { Pipe, PipeTransform } from '@angular/core';
import { formatMoney, formatWholeMoney } from './money';

/** `{{ monto | money }}`: "US$ 180.000"; `{{ monto | money: 'whole' }}`, redondeado a dólares. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: number | null | undefined, mode: 'exact' | 'whole' = 'exact'): string {
    if (value === null || value === undefined) {
      return '—';
    }
    return mode === 'whole' ? formatWholeMoney(value) : formatMoney(value);
  }
}
