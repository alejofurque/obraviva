/**
 * Fecha de hoy en la zona horaria del navegador, como yyyy-mm-dd.
 *
 * <p>No se usa toISOString: convierte a UTC y en Argentina, a la noche, devuelve el día
 * siguiente o el anterior según la hora.
 */
export function todayLocal(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}
