import { Pipe, PipeTransform } from '@angular/core';

/**
 * Los meses abreviados de toda la app: "sep", no "sept". El `DatePipe` de es-AR abrevia
 * septiembre como "sept." y las funciones propias como "sep"; con las dos convivían "20 sept"
 * y "fin previsto sep" en la misma casa. Todo lo corto sale de acá.
 */
export const SHORT_MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'] as const;

/** El mes abreviado de un índice 0-11 ("sep"); con `capitalized`, "Sep". */
export function shortMonth(index: number, capitalized = false): string {
  const month = SHORT_MONTHS[index] ?? '';
  return capitalized ? month.charAt(0).toUpperCase() + month.slice(1) : month;
}

/**
 * Una fecha como `Date`. "2026-09-20" es un día local (no UTC: en Argentina, a la noche,
 * pasaría al día anterior); con hora ("2026-09-20T12:55:00Z"), un instante.
 */
function toDate(value: string | Date): Date | null {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (day) {
    return new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3]));
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/**
 * Formato corto con el mes abreviado de la app. Entiende los formatos que se usan:
 * `d MMM` ("20 sep"), `d MMM y` ("20 sep 2026"), `d MMM yy`, `MMM y` ("sep 2026") y
 * `d MMM y, HH:mm`. Nulo si no hay fecha.
 */
export function formatShortDate(value: string | Date | null | undefined, format = 'd MMM y'): string | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const date = toDate(value);
  if (!date) {
    return null;
  }
  const two = (n: number) => String(n).padStart(2, '0');
  return format.replace(/HH:mm|MMM|yy|y|d/g, token => {
    switch (token) {
      case 'HH:mm': return `${two(date.getHours())}:${two(date.getMinutes())}`;
      case 'MMM': return shortMonth(date.getMonth());
      case 'yy': return String(date.getFullYear()).slice(2);
      case 'y': return String(date.getFullYear());
      default: return String(date.getDate());
    }
  });
}

/** `{{ fecha | shortDate: 'd MMM y' }}`, con "sep" y no "sept.". */
@Pipe({ name: 'shortDate' })
export class ShortDatePipe implements PipeTransform {
  transform(value: string | Date | null | undefined, format = 'd MMM y'): string | null {
    return formatShortDate(value, format);
  }
}
