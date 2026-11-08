import { DashboardResponse, ExpenseResponse, TrafficLight, UnitDashboardRow } from '../../core/api/api.models';
import { formatWholeMoney } from '../../shared/money/money';
import { parseAmount } from './amounts';
import { SHORT_MONTHS } from '../../shared/time/short-date';

/**
 * Costo final estimado: lo gastado dividido por el avance. Es una regla de tres explícita,
 * "si se sigue gastando al mismo ritmo que se avanza", no una proyección. Sin avance no hay
 * estimado; al 100% da exactamente lo gastado.
 */
export function finalCost(spent: number, progressPercent: number): number | null {
  return progressPercent > 0 ? spent / (progressPercent / 100) : null;
}

export interface Estimate {
  /** Nulo sin avance. */
  value: number | null;
  /** Contra el presupuesto; nulo sin estimado o con el presupuesto incompleto. */
  difference: number | null;
  differencePercent: number | null;
  /** "Presupuesto incompleto: 11 de 12 etapas sin presupuesto"; nulo si está completo. */
  incomplete: string | null;
}

function compare(value: number | null, budget: number, incomplete: string | null): Estimate {
  if (value === null || incomplete !== null || budget <= 0) {
    return { value, difference: null, differencePercent: null, incomplete };
  }
  const difference = value - budget;
  return { value, difference, differencePercent: difference / budget * 100, incomplete };
}

/** El estimado de una casa, contra su presupuesto si todas sus etapas lo tienen. */
export function houseEstimate(budget: number, spent: number, progressPercent: number,
                              stagesWithoutBudget: number, stageCount: number): Estimate {
  const incomplete = stagesWithoutBudget > 0
    ? `Presupuesto incompleto: ${stagesWithoutBudget} de ${stageCount} etapas sin presupuesto`
    : null;
  return compare(finalCost(spent, progressPercent), budget, incomplete);
}

export interface EstimatePart {
  budget: number | null;
  spent: number;
  progressPercent: number;
  stagesWithoutBudget: number;
}

/**
 * El estimado del desarrollo es la suma de sus partes (cada casa y las obras comunes): una
 * parte sin avance cuenta con su presupuesto. No se divide por el avance del desarrollo, que
 * promedia casas y deja afuera lo común.
 */
export function developmentEstimate(parts: EstimatePart[]): Estimate {
  const counted = parts.filter(part => part.spent > 0 || (part.budget ?? 0) > 0);
  if (counted.length === 0) {
    return { value: null, difference: null, differencePercent: null, incomplete: null };
  }
  const value = counted.reduce((sum, part) =>
    sum + (finalCost(part.spent, part.progressPercent) ?? Math.max(part.budget ?? 0, part.spent)), 0);
  const budget = counted.reduce((sum, part) => sum + (part.budget ?? 0), 0);
  const missing = counted.filter(part => part.stagesWithoutBudget > 0).length;
  const incomplete = missing > 0
    ? `Presupuesto incompleto: ${missing === 1 ? '1 parte tiene' : missing + ' partes tienen'} etapas sin presupuesto`
    : null;
  return compare(value, budget, incomplete);
}

/** "+US$ 13.200 sobre el presupuesto (+7%)" o "−US$ 4.000 bajo el presupuesto (−2%)". */
export function differenceLine(estimate: Estimate, money: (value: number) => string): string | null {
  if (estimate.difference === null || estimate.differencePercent === null) {
    return null;
  }
  const percent = Math.round(Math.abs(estimate.differencePercent));
  if (percent === 0) {
    return 'En línea con el presupuesto (menos de 1% de diferencia)';
  }
  return estimate.difference > 0
    ? `+${money(estimate.difference)} sobre el presupuesto (+${percent}%)`
    : `−${money(-estimate.difference)} bajo el presupuesto (−${percent}%)`;
}

export type SpendState = 'ok' | 'watch' | 'over' | 'none' | 'unbudgeted';

/** El estado de una etapa o de una casa en palabras. Las alertas van en ámbar, nunca en rojo. */
export function spendState(light: TrafficLight, spent: number): { state: SpendState; label: string } {
  if (light === 'NO_BUDGET') return { state: 'unbudgeted', label: 'Sin presupuesto' };
  if (spent === 0) return { state: 'none', label: 'Sin gastos' };
  if (light === 'RED') return { state: 'over', label: 'Gasta más de lo que avanza' };
  if (light === 'YELLOW') return { state: 'watch', label: 'Atención' };
  return { state: 'ok', label: 'En línea' };
}

export interface CurvePoint {
  month: string;
  monthlyAmount: number;
  accumulated: number;
}

export interface CurveChart {
  months: string[];
  /** Acumulado previsto por mes; nulo si no hay curva prevista. */
  planned: number[] | null;
  /** Acumulado gastado por mes, hasta el mes actual; nulo después. */
  real: (number | null)[];
  max: number;
}

function nextMonth(month: string): string {
  const [year, number] = month.split('-').map(Number);
  return number === 12 ? `${year + 1}-01` : `${year}-${String(number + 1).padStart(2, '0')}`;
}

/**
 * Las dos curvas sobre los mismos meses, del primero al último sin saltos: la prevista hasta
 * su fin y la gastada hasta el mes actual. Un mes sin gastos repite el acumulado.
 */
export function curveChart(planned: CurvePoint[], real: CurvePoint[], currentMonth: string): CurveChart {
  const known = [...planned.map(point => point.month), ...real.map(point => point.month)];
  if (known.length === 0) {
    return { months: [], planned: null, real: [], max: 0 };
  }
  const first = known.reduce((a, b) => (a < b ? a : b));
  const lastKnown = known.reduce((a, b) => (a > b ? a : b));
  const last = currentMonth > lastKnown && real.length > 0 ? currentMonth : lastKnown;
  const months: string[] = [];
  for (let month = first; month <= last; month = nextMonth(month)) {
    months.push(month);
  }
  const accumulate = (points: CurvePoint[], until: string | null) => {
    const byMonth = new Map(points.map(point => [point.month, point.accumulated]));
    let value = 0;
    return months.map(month => {
      if (until !== null && month > until) return null;
      value = byMonth.get(month) ?? value;
      return value;
    });
  };
  const plannedLine = planned.length > 0 ? accumulate(planned, null) as number[] : null;
  const realLine = accumulate(real, currentMonth);
  const values = [...(plannedLine ?? []), ...realLine.filter((value): value is number => value !== null)];
  return { months, planned: plannedLine, real: realLine, max: Math.max(0, ...values) };
}

/** Lo de un mes de una curva: el monto del mes, cero si no hubo. */
export function monthAmount(points: CurvePoint[], month: string): number {
  return points.find(point => point.month === month)?.monthlyAmount ?? 0;
}

export interface PastedAmount {
  /** Lo que se pegó en esa línea, tal cual. */
  text: string;
  /** Nulo: línea vacía, no cambia la etapa. */
  amount: number | null;
  error: string | null;
}

/**
 * Una columna de montos pegada desde Excel, en el orden de las etapas. Se lee como en
 * Argentina (punto de miles, coma decimal) con la misma regla que un campo de monto; se
 * aceptan "US$" y "$" que deja el formato de moneda. Un número escrito a la inglesa
 * ("12,000.50") no se adivina: es un error, no un monto mal leído.
 */
export function parsePastedAmounts(text: string): PastedAmount[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  while (lines.length > 0 && lines[lines.length - 1].trim() === '') {
    lines.pop();
  }
  return lines.map(line => {
    if (line.trim().includes('\t')) {
      return { text: line, amount: null, error: 'Pegá una sola columna' };
    }
    const cleaned = line.replace(/US\$|\$/g, '').replace(/[\s ]/g, '');
    const amount = parseAmount(cleaned);
    if (amount === 'invalid' || (amount !== null && amount < 0)) {
      return { text: line, amount: null, error: 'No es un monto: se escribe como 12.000 o 12.000,50' };
    }
    return { text: line, amount, error: null };
  });
}

/** Un indicador del Económico: el número, su lectura y, si hace falta, una barra con su marca. */
export interface EconomicsKpi {
  label: string;
  value: string;
  /** Ámbar: el número es una alerta (gasta más de lo previsto). Nunca rojo. */
  warn?: boolean;
  lines: string[];
  /** Una aclaración más chica, como la regla del estimado. */
  note?: string;
  /** Barra de 0 a 100 con la marca del avance; en ámbar si gasta más rápido de lo que avanza. */
  bar?: { fill: number; mark: number | null; warn: boolean; label: string };
}

/** El mismo umbral que el semáforo amarillo del servidor: diez puntos de gasto sobre el avance. */
const WATCH_DEVIATION = 10;

export const ESTIMATE_RULE = 'Si se sigue gastando al mismo ritmo que se avanza (regla de tres).';

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/**
 * "Gastado" con su barra contra el avance. Con el presupuesto incompleto no se da el porcentaje:
 * lo gastado en etapas sin presupuesto contra el de las que sí lo tienen no dice nada.
 */
export function spentKpi(spent: number, usedPercent: number | null, progressPercent: number,
                         budgetComplete = true): EconomicsKpi {
  const progress = Math.round(progressPercent);
  if (usedPercent === null || !budgetComplete) {
    return { label: 'Gastado', value: formatWholeMoney(spent), lines: [`Avance ${progress}%`] };
  }
  const used = Math.round(usedPercent);
  return {
    label: 'Gastado',
    value: formatWholeMoney(spent),
    lines: [`${used}% del presupuesto · avance ${progress}%`],
    bar: {
      fill: Math.min(usedPercent, 100),
      mark: Math.min(progressPercent, 100),
      warn: usedPercent - progressPercent > WATCH_DEVIATION,
      label: `${used}% del presupuesto gastado con ${progress}% de avance`,
    },
  };
}

/** "Costo final estimado": la regla de tres, contra el presupuesto si se puede comparar. */
export function estimateKpi(estimate: Estimate): EconomicsKpi {
  if (estimate.value === null) {
    return { label: 'Costo final estimado', value: '—', lines: ['Sin avance todavía'] };
  }
  const line = differenceLine(estimate, formatWholeMoney) ?? estimate.incomplete;
  return {
    label: 'Costo final estimado',
    value: formatWholeMoney(estimate.value),
    warn: Math.round(estimate.differencePercent ?? 0) > 0,
    lines: line === null ? [] : [line],
    note: ESTIMATE_RULE,
  };
}

/** "Este mes": lo gastado y, si hay curva prevista, lo que se esperaba. Neutro: no es alerta. */
export function monthKpi(expenses: ExpenseResponse[], planned: CurvePoint[], month: string): EconomicsKpi {
  const ofMonth = expenses.filter(expense => expense.incurredOn.startsWith(month));
  const spent = ofMonth.reduce((sum, expense) => sum + expense.amount, 0);
  const count = ofMonth.length === 0 ? 'Sin gastos este mes' : plural(ofMonth.length, 'gasto', 'gastos');
  const expected = planned.length > 0 ? ` · se esperaban ${formatWholeMoney(monthAmount(planned, month))}` : '';
  return { label: 'Este mes', value: formatWholeMoney(spent), lines: [count + expected] };
}

/** Los cuatro indicadores de la casa. */
export function houseKpis(panel: DashboardResponse, expenses: ExpenseResponse[], month: string): EconomicsKpi[] {
  const total = panel.stages.length;
  const budgeted = panel.stages.filter(stage => stage.budgetAmount !== null).length;
  const budget: EconomicsKpi = budgeted === 0
    ? { label: 'Presupuesto', value: 'Sin cargar', lines: ['Cargalo con "Editar presupuesto"'] }
    : {
      label: 'Presupuesto',
      value: formatWholeMoney(panel.totalBudget),
      lines: [budgeted === total ? plural(total, 'etapa presupuestada', 'etapas presupuestadas')
        : `${budgeted} de ${total} etapas presupuestadas`],
    };
  return [
    budget,
    spentKpi(panel.totalSpent, panel.budgetUsedPercent, panel.overallProgressPercent, budgeted === total),
    estimateKpi(houseEstimate(panel.totalBudget, panel.totalSpent, panel.overallProgressPercent, total - budgeted, total)),
    monthKpi(expenses, panel.plannedCurve, month),
  ];
}

/** Lo que la curva prevista no muestra, en una línea; nulo si no hay nada que aclarar. */
export function curveNote(panel: DashboardResponse): string | null {
  if (panel.plannedCurve.length === 0) {
    return panel.stages.some(stage => stage.budgetAmount !== null)
      ? 'Sin curva prevista: cargá las fechas previstas de las etapas en Etapas.'
      : 'Sin curva prevista: cargá el presupuesto y las fechas previstas de las etapas.';
  }
  if (panel.undatedStages > 0) {
    return `La curva prevista no incluye ${plural(panel.undatedStages, 'etapa', 'etapas')} sin fechas previstas (${formatWholeMoney(panel.undatedBudget)}).`;
  }
  return null;
}

/** Una casa o las obras comunes en el Económico del desarrollo: lo que entra en su estimado. */
export function partOf(row: UnitDashboardRow): EstimatePart {
  return { budget: row.budgetAmount, spent: row.spentAmount, progressPercent: row.progressPercent, stagesWithoutBudget: row.stagesWithoutBudget };
}

/**
 * El estimado de una fila: la regla de tres si avanzó; sin avance, su presupuesto tal cual, que
 * es lo único que se sabe (y así entra en la suma del desarrollo).
 */
export function partEstimate(row: UnitDashboardRow): Estimate {
  if (row.progressPercent > 0) {
    return houseEstimate(row.budgetAmount ?? 0, row.spentAmount, row.progressPercent, row.stagesWithoutBudget, row.stageCount);
  }
  return { value: row.budgetAmount, difference: null, differencePercent: null, incomplete: null };
}

/** La diferencia en una celda: "+US$ 13.200 (+7%)", "Presupuesto incompleto", "Sin avance aún" o nada. */
export function shortDifference(row: UnitDashboardRow, estimate: Estimate): string | null {
  if (row.progressPercent === 0) return row.budgetAmount === null ? null : 'Sin avance aún';
  if (estimate.incomplete !== null) return 'Sin comparar';
  if (estimate.difference === null || estimate.differencePercent === null) return null;
  const percent = Math.round(Math.abs(estimate.differencePercent));
  if (percent === 0) return 'En línea';
  const sign = estimate.difference > 0 ? '+' : '−';
  return `${sign}${formatWholeMoney(Math.abs(estimate.difference))} (${sign}${percent}%)`;
}

const ALERT_LABELS: Partial<Record<TrafficLight, { state: SpendState; label: string }>> = {
  RED: { state: 'over', label: 'Gasta más de lo que avanza' },
  YELLOW: { state: 'watch', label: 'Atención' },
};

/**
 * El estado de una casa o de las obras comunes: el de su peor etapa, con su nombre cuando es una
 * alerta. El total puede ir en línea mientras una etapa ya se fue.
 */
export function partState(row: UnitDashboardRow): { state: SpendState; label: string; detail: string | null } {
  if (row.spentAmount === 0 && row.progressPercent === 0) return { state: 'none', label: 'Sin empezar', detail: null };
  const alert = ALERT_LABELS[row.worstStageLight];
  if (alert) return { ...alert, detail: row.worstStageName };
  if (row.worstStageLight === 'NO_BUDGET') return { state: 'unbudgeted', label: 'Sin presupuesto', detail: null };
  // Que la única etapa presupuestada vaya bien no dice que la casa vaya bien.
  if (row.stagesWithoutBudget > 0) {
    return { state: 'unbudgeted', label: 'Presupuesto incompleto', detail: `${row.stagesWithoutBudget} de ${row.stageCount} etapas sin presupuesto` };
  }
  return { state: 'ok', label: 'En línea', detail: null };
}

/** Los cuatro indicadores del desarrollo. */
export function developmentKpis(panel: DashboardResponse): EconomicsKpi[] {
  const rows = [...panel.units, ...(panel.commonWorks ? [panel.commonWorks] : [])];
  const parts = [...rows.map(partOf), { budget: null, spent: panel.generalSpent, progressPercent: 0, stagesWithoutBudget: 0 }];
  const complete = rows.every(row => row.stagesWithoutBudget === 0);
  const houses = plural(panel.units.length, 'casa', 'casas');
  const commons = panel.commonWorks?.budgetAmount;
  const alerts = panel.alerts.length;
  return [
    {
      label: 'Presupuesto total',
      value: panel.totalBudget > 0 ? formatWholeMoney(panel.totalBudget) : 'Sin cargar',
      lines: [commons ? `${houses} · obras comunes ${formatWholeMoney(commons)}` : houses],
    },
    spentKpi(panel.totalSpent, panel.budgetUsedPercent, panel.overallProgressPercent, complete),
    estimateKpi(developmentEstimate(parts)),
    {
      label: 'Para mirar',
      value: alerts === 0 ? 'Nada' : plural(alerts, 'etapa', 'etapas'),
      warn: alerts > 0,
      lines: [alerts === 0 ? 'Ninguna etapa gasta más de lo que avanza' : 'Gastan más rápido de lo que avanzan'],
    },
  ];
}

export interface MonthBar {
  month: string;
  label: string;
  amount: number;
  /** Alto relativo al mes más alto, de 0 a 100. */
  height: number;
  /** El mes en curso: todavía puede crecer. */
  current: boolean;
}

/**
 * "Gasto por mes": hasta los últimos doce meses, del primero con gastos al actual, con los meses
 * sin gastos en cero. El promedio es el de los meses cerrados (hasta seis), sin el actual.
 */
export function monthlyBars(points: CurvePoint[], currentMonth: string, limit = 12): { bars: MonthBar[]; average: { months: number; amount: number } | null } {
  if (points.length === 0) return { bars: [], average: null };
  const byMonth = new Map(points.map(point => [point.month, point.monthlyAmount]));
  const months: string[] = [];
  for (let month = points[0].month; month <= currentMonth; month = nextMonth(month)) {
    months.push(month);
  }
  const shown = months.slice(-limit);
  const max = Math.max(0, ...shown.map(month => byMonth.get(month) ?? 0));
  const bars = shown.map(month => {
    const amount = byMonth.get(month) ?? 0;
    return {
      month, amount, current: month === currentMonth,
      label: SHORT_MONTHS[Number(month.slice(5, 7)) - 1],
      height: max === 0 ? 0 : amount / max * 100,
    };
  });
  const closed = months.filter(month => month < currentMonth).slice(-6);
  const average = closed.length === 0 ? null
    : { months: closed.length, amount: closed.reduce((sum, month) => sum + (byMonth.get(month) ?? 0), 0) / closed.length };
  return { bars, average };
}

