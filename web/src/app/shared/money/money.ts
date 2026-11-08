/**
 * La moneda del sistema: dólares. Los montos se cargan y se muestran en US$, en todas las
 * pantallas, desde acá. Gastos en pesos con su cotización quedan como evolución:
 * hoy no hay conversión, sólo una moneda.
 */
export const CURRENCY = 'USD';

const WHOLE = new Intl.NumberFormat('es-AR', { style: 'currency', currency: CURRENCY, maximumFractionDigits: 0 });
const CENTS = new Intl.NumberFormat('es-AR', { style: 'currency', currency: CURRENCY, minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** "US$ 180.000"; con centavos, siempre dos: "US$ 180.000,50". */
export function formatMoney(value: number): string {
  return Number.isInteger(value) ? WHOLE.format(value) : CENTS.format(value);
}

/** Redondeado a dólares enteros, para totales y estimados: "US$ 193.200". */
export function formatWholeMoney(value: number): string {
  return WHOLE.format(Math.round(value));
}

/** Para un indicador chico: "US$ 41,2 M", "US$ 850 mil", "US$ 900". */
export function compactMoney(value: number): string {
  const sign = value < 0 ? '-' : '';
  const amount = Math.abs(value);
  // El mismo espacio duro que usa Intl: "US$" no se separa del número.
  if (amount >= 1_000_000) {
    return `${sign}US$ ${(amount / 1_000_000).toLocaleString('es-AR', { maximumFractionDigits: 1 })} M`;
  }
  if (amount >= 1_000) {
    return `${sign}US$ ${Math.round(amount / 1_000).toLocaleString('es-AR')} mil`;
  }
  return `${sign}US$ ${Math.round(amount).toLocaleString('es-AR')}`;
}
