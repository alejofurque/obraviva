/**
 * Montos como se escriben en Argentina: punto para los miles y coma para los decimales
 * ("6.300.000,50"). Se leen así al cargarlos y se muestran así al editarlos, para que un
 * monto que se abre y se guarda sin tocar no cambie.
 */

/** Monto de un campo de formulario: vacío es "sin monto", no cero. */
export function parseAmount(text: string): number | null | 'invalid' {
  const trimmed = text.trim();
  if (trimmed === '') {
    return null;
  }
  // Sólo dígitos con puntos de miles y, opcional, una coma con hasta dos decimales.
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,2})?$|^\d+(,\d{1,2})?$/.test(trimmed)) {
    return 'invalid';
  }
  const value = Number(trimmed.replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(value) ? value : 'invalid';
}

const FORMAT = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2, useGrouping: true });
const FORMAT_CENTS = new Intl.NumberFormat('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true });

/** 6300000 a "6.300.000" y 6300000.5 a "6.300.000,50": lo que se ve al editar un monto. */
export function formatAmount(value: number): string {
  return Number.isInteger(value) ? FORMAT.format(value) : FORMAT_CENTS.format(value);
}
