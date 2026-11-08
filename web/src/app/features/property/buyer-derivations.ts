import { PublicMedia, PublicPlace, PublicProgressEntry, PublicStage, PublicStageChange, PublicUnitView } from '../../core/api/api.models';
import { BUYER_STAGE_STATUS_LABELS } from '../../shared/ui/stage-status';
import { todayLocal } from '../../shared/time/local-date';
import { mediaDay, monthsChronological, placesWithMaterial } from './places-derivations';
import { constructionState, trackedEntries } from './property-derivations';
import { SHORT_MONTHS } from '../../shared/time/short-date';

/**
 * Lecturas de "Mi casa", la pantalla de la familia. Funciones puras sobre la vista que manda
 * el servidor: tranquilizan, cuentan el mes e ilusionan con lo que viene, pero nunca afirman
 * algo que los datos no dicen. Si un dato no está, la pieza no aparece.
 */

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
  'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** El título de la portada: lo que es cierto hoy. */
export function heroTitle(view: PublicUnitView): string {
  switch (constructionState(view)) {
    case 'FINISHED': return 'Tu casa está lista.';
    case 'NOT_STARTED': return 'Tu casa está por empezar.';
    default: return 'Tu casa avanza.';
  }
}

/** La frase de la portada: el último avance de la casa, tal cual; uno de obras comunes sólo si la casa no tiene ninguno. */
export function heroEntry(view: PublicUnitView): PublicProgressEntry | null {
  const tracked = trackedEntries(view.timeline);
  return tracked.find(entry => !entry.common) ?? tracked[0] ?? null;
}

export type ScheduleKind = 'ON_TIME' | 'DELAYED';

/** Cómo va la obra contra lo previsto. Nulo si no se puede afirmar ni una cosa ni la otra. */
export interface Schedule {
  kind: ScheduleKind;
  /** El motivo de una demora, en palabras de la obra. Nulo cuando va en tiempo. */
  reason: string | null;
}

/**
 * "Con demora" si una etapa está demorada, si una sin terminar ya pasó su fin previsto o si
 * la entrega estimada ya pasó; el motivo es el avance que la marcó demorada, o la etapa que
 * se pasó. "En tiempo" sólo si todas las etapas sin terminar tienen fin previsto, ninguna
 * está demorada ni vencida y la última termina antes del mes de entrega. Si no, nada.
 */
export function schedule(view: PublicUnitView, today: string = todayLocal()): Schedule | null {
  const state = constructionState(view);
  if (state === 'FINISHED') return null;
  const delivery = deliveryMonth(view);
  const open = view.stages.filter(stage => stage.status !== 'COMPLETED');
  const delayed = open.find(stage => stage.status === 'DELAYED');
  if (delayed) {
    const entry = view.timeline.find(item => !item.common && item.stageName === delayed.name
      && item.stageChange?.statusAfter === 'DELAYED');
    return { kind: 'DELAYED', reason: entry?.description ?? `${delayed.name} lleva más de lo previsto.` };
  }
  const overdue = open.find(stage => stage.plannedEnd !== null && stage.plannedEnd < today);
  if (overdue) {
    return { kind: 'DELAYED', reason: `${overdue.name} lleva más de lo previsto.` };
  }
  if (delivery && delivery < today.slice(0, 7)) {
    return { kind: 'DELAYED', reason: 'Ya pasó el mes de entrega estimado.' };
  }
  if (!delivery || open.length === 0 || open.some(stage => stage.plannedEnd === null)) {
    return null;
  }
  const lastEnd = open.map(stage => stage.plannedEnd!).sort().at(-1)!;
  return lastEnd.slice(0, 7) <= delivery ? { kind: 'ON_TIME', reason: null } : null;
}

/** El mes de entrega de la casa o, si no tiene, el del desarrollo. */
export function deliveryMonth(view: PublicUnitView): string | null {
  return view.details.estimatedDelivery ?? view.development.estimatedDelivery ?? null;
}

/** "Noviembre 2027". */
export function longYearMonth(yearMonth: string): string {
  const [year, month] = yearMonth.split('-');
  const name = MONTHS[Number(month) - 1];
  return `${name[0].toUpperCase()}${name.slice(1)} ${year}`;
}

/** "Faltan unos 13 meses.", "Falta un mes.", "Es este mes." Nulo si ya pasó. */
export function monthsLeft(yearMonth: string, today: string = todayLocal()): string | null {
  const [year, month] = yearMonth.split('-').map(Number);
  const [nowYear, nowMonth] = today.split('-').map(Number);
  const months = (year - nowYear) * 12 + (month - nowMonth);
  if (months < 0) return null;
  if (months === 0) return 'Es este mes.';
  return months === 1 ? 'Falta un mes.' : `Faltan unos ${months} meses.`;
}

/** "3 avances este mes", contando sólo los de la casa; nulo si no hubo. */
export function entriesThisMonth(view: PublicUnitView, today: string = todayLocal()): string | null {
  const count = trackedEntries(view.timeline).filter(entry => !entry.common && entry.date.startsWith(today.slice(0, 7))).length;
  if (count === 0) return null;
  return count === 1 ? '1 avance este mes' : `${count} avances este mes`;
}

/** "Instalación eléctrica: 35% → 50%", o el estado si cambió sin porcentaje. Nulo si no tocó la etapa. */
export function changeLine(stageName: string | null, change: PublicStageChange | null): string | null {
  if (!change || !stageName) return null;
  if (change.percentBefore !== null && change.percentAfter !== null && change.percentBefore !== change.percentAfter) {
    return `${stageName}: ${change.percentBefore}% → ${change.percentAfter}%`;
  }
  if (change.statusBefore && change.statusAfter && change.statusBefore !== change.statusAfter) {
    const after = BUYER_STAGE_STATUS_LABELS[change.statusAfter as keyof typeof BUYER_STAGE_STATUS_LABELS] ?? change.statusAfter;
    return `${stageName}: ${after.toLowerCase()}`;
  }
  return null;
}

/** Un hecho del mes: lo que pasó, contado por el avance que lo publicó. */
export interface MonthFact {
  title: string;
  text: string;
  date: string;
}

/** Una foto del mosaico del mes, con su lugar y su día. */
export interface MonthPhoto {
  media: PublicMedia;
  sector: string | null;
  day: string;
}

/** "Este mes en tu casa": el mes con avances de la casa más reciente, sus hechos y sus fotos. */
export interface MonthStory {
  month: string;
  facts: MonthFact[];
  photos: MonthPhoto[];
}

export function monthStory(view: PublicUnitView): MonthStory | null {
  const own = trackedEntries(view.timeline).filter(entry => !entry.common);
  if (own.length === 0) return null;
  const month = own.map(entry => entry.date.slice(0, 7)).sort().at(-1)!;
  const entries = own.filter(entry => entry.date.startsWith(month));
  const facts = entries.map(entry => ({
    title: changeLine(entry.stageName, entry.stageChange) ?? entry.stageName ?? 'Avance de obra',
    text: entry.description,
    date: entry.date,
  }));
  const photos: MonthPhoto[] = [];
  const seen = new Set<string>();
  const add = (media: PublicMedia, sector: string | null) => {
    if (media.kind === 'PHOTO' && !seen.has(media.url)) {
      seen.add(media.url);
      photos.push({ media, sector, day: mediaDay(media) });
    }
  };
  for (const place of view.places) {
    for (const item of place.months.filter(candidate => candidate.month === month)) {
      item.photos.forEach(photo => add(photo, place.name === 'General' ? null : place.name));
    }
  }
  // Las fotos de un avance sin lugar no están en places: entran sin rótulo de lugar.
  entries.forEach(entry => entry.photos.forEach(photo => add(photo, null)));
  photos.sort((a, b) => b.day.localeCompare(a.day));
  return { month, facts, photos };
}

/** "Octubre 2026". */
export function monthTitle(month: string): string {
  return longYearMonth(month);
}

/** "20 oct". */
export function shortDay(day: string): string {
  return `${Number(day.slice(8, 10))} ${SHORT_MONTHS[Number(day.slice(5, 7)) - 1]}`;
}

/** "Lo que viene": la próxima etapa con lo que incluye, cuándo está prevista y las dos que siguen. */
export interface Upcoming {
  stage: PublicStage;
  /** "diciembre"; nulo si no tiene fechas previstas o si ese mes ya pasó. */
  when: string | null;
  after: string | null;
  last: string | null;
}

export function upcoming(view: PublicUnitView, today: string = todayLocal()): Upcoming | null {
  if (constructionState(view) === 'FINISHED') return null;
  const ordered = [...view.stages].sort((a, b) => a.orderIndex - b.orderIndex);
  const index = ordered.findIndex(stage => stage.status === 'PENDING');
  if (index < 0) return null;
  const stage = ordered[index];
  const date = stage.plannedStart ?? stage.plannedEnd;
  const rest = ordered.slice(index + 1).filter(item => item.status === 'PENDING');
  return {
    stage,
    // Un mes previsto que ya pasó no se nombra: la demora se cuenta en la entrega, no acá.
    when: date && date.slice(0, 7) >= today.slice(0, 7) ? MONTHS[Number(date.slice(5, 7)) - 1] : null,
    after: rest[0]?.name ?? null,
    last: rest.length > 1 ? rest.at(-1)!.name : null,
  };
}

/** Un lugar como tarjeta grande: su foto más nueva, si tiene 3D y de qué meses tiene material. */
export interface PlaceCard {
  name: string;
  photo: PublicMedia | null;
  has3d: boolean;
  months: string;
}

export function placeCards(places: PublicPlace[]): PlaceCard[] {
  return placesWithMaterial(places).map(place => {
    const months = monthsChronological(place);
    const withPhoto = [...months].reverse().find(month => month.photos.length > 0);
    const first = SHORT_MONTHS[Number(months[0].month.slice(5, 7)) - 1];
    const last = SHORT_MONTHS[Number(months.at(-1)!.month.slice(5, 7)) - 1];
    const label = (name: string) => name[0].toUpperCase() + name.slice(1);
    return {
      name: place.name,
      photo: withPhoto ? withPhoto.photos.at(-1)! : null,
      has3d: months.some(month => month.tour),
      months: months.length === 1 ? label(first) : `${label(first)} – ${label(last)}`,
    };
  });
}

/** Una etapa en la grilla del camino: terminada, en curso (demorada también, en tono neutro) o por venir. */
export interface StageCell {
  number: string;
  name: string;
  state: 'DONE' | 'NOW' | 'NEXT';
  label: string;
}

export function stageCells(stages: PublicStage[]): StageCell[] {
  return [...stages].sort((a, b) => a.orderIndex - b.orderIndex).map(stage => {
    const state = stage.status === 'COMPLETED' ? 'DONE' : stage.status === 'PENDING' ? 'NEXT' : 'NOW';
    return {
      number: String(stage.orderIndex).padStart(2, '0'),
      name: stage.name,
      state,
      label: state === 'DONE' ? 'Terminada' : state === 'NOW' ? 'En curso' : 'Por venir',
    };
  });
}

/** El nombre de pila de quien mira, para el saludo; nulo si no hay. */
export function greetingName(fullName: string | null | undefined): string | null {
  const first = (fullName ?? '').trim().split(/\s+/)[0];
  return first ? first : null;
}

/**
 * "hoy", "ayer", "hace 3 días" y, pasada la semana, "el 8 de octubre", para un día (yyyy-mm-dd).
 * Se cuenta con días de calendario: un día sin hora no se corre por la zona horaria.
 */
export function sinceLabel(day: string, today: string = todayLocal()): string {
  const days = Math.round((Date.parse(`${today}T12:00:00`) - Date.parse(`${day}T12:00:00`)) / 86_400_000);
  if (days <= 0) return 'hoy';
  if (days === 1) return 'ayer';
  if (days < 7) return `hace ${days} días`;
  return `el ${Number(day.slice(8, 10))} de ${MONTHS[Number(day.slice(5, 7)) - 1]}`;
}
