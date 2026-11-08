import {
  DevelopmentKind,
  PublicCapture,
  PublicMedia,
  PublicProgressEntry,
  PublicStage,
  PublicUnitDetails,
  PublicUnitView,
} from '../../core/api/api.models';
import { imageVariantOf } from '../../shared/media/variant.pipe';
import { SHORT_MONTHS } from '../../shared/time/short-date';

/**
 * Lecturas de la vista de una propiedad que usan varias pestañas. Funciones puras: todo
 * sale de los datos del backend, y ninguna completa con valores que parezcan reales.
 */

export type ConstructionState = 'NOT_STARTED' | 'IN_CONSTRUCTION' | 'FINISHED';

export const CONSTRUCTION_STATE_LABELS: Record<ConstructionState, string> = {
  NOT_STARTED: 'Por comenzar',
  IN_CONSTRUCTION: 'En construcción',
  FINISHED: 'Terminada',
};

/**
 * Estado general de la obra. Terminada sólo si hay etapas y todas están completas;
 * por comenzar si no hay avance ni historia; en construcción en cualquier otro caso.
 */
export function constructionState(view: PublicUnitView): ConstructionState {
  if (view.stages.length > 0 && view.stages.every(stage => stage.status === 'COMPLETED')) {
    return 'FINISHED';
  }
  if (view.overallProgressPercent === 0 && view.timeline.length === 0) {
    return 'NOT_STARTED';
  }
  return 'IN_CONSTRUCTION';
}

/** Fecha del avance más reciente del seguimiento, o nula si todavía no hay. */
export function lastUpdate(view: PublicUnitView): string | null {
  return trackedEntries(view.timeline)[0]?.date ?? null;
}

/** La etapa que sigue a la actual en el orden de obra. */
export function nextStage(view: PublicUnitView): PublicStage | null {
  const current = view.currentStage;
  if (!current) {
    return null;
  }
  return [...view.stages]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .find(stage => stage.orderIndex > current.orderIndex && stage.status !== 'COMPLETED') ?? null;
}

/** Fotos de la galería de la más vieja a la más nueva, por fecha de avance o de subida. */
export function photosChronological(view: PublicUnitView): PublicMedia[] {
  return view.gallery
    .filter(media => media.kind === 'PHOTO')
    .map(media => ({ media, when: media.entryDate ?? media.uploadedAt.slice(0, 10) }))
    .sort((a, b) => a.when.localeCompare(b.when))
    .map(item => item.media);
}

/**
 * Imagen principal: la portada de la casa y, si no hay, la foto más reciente de la obra. Nunca
 * la galería: es material de venta (renders, el modelo terminado) y no puede pasar por la obra.
 */
export function heroImage(view: PublicUnitView): string | null {
  if (view.details.coverUrl) {
    // La portada es grande: su versión de pantalla, o el original si todavía no está.
    return imageVariantOf(view.details, 'cover', 'screen');
  }
  // La portada es grande: la versión de pantalla de la foto, o el original si no está.
  const photos = photosChronological(view);
  const latest = photos.at(-1);
  return latest ? latest.screenUrl ?? latest.url : null;
}

/**
 * Captura que muestra la obra en una fecha: la última tomada ese día o antes. Si todas
 * son posteriores, la primera, que es lo más cercano que existe.
 */
export function captureIndexAt(captures: PublicCapture[], date: string): number {
  if (captures.length === 0) {
    return -1;
  }
  let index = 0;
  captures.forEach((capture, i) => {
    if (capture.date <= date) {
      index = i;
    }
  });
  return index;
}

export interface HistoryMonth {
  /** Año y mes, por ejemplo 2026-10; `antes` en el grupo de la carga inicial. */
  month: string;
  /** Avances del mes, del más nuevo al más viejo; en la carga inicial, del más viejo al más nuevo. */
  entries: PublicProgressEntry[];
  /** Sectores con recorrido 3D grabado ese mes, en el orden de la casa. */
  sectors: string[];
  /** "Antes del seguimiento": la carga inicial, con mes y año en vez del día. */
  beforeTracking?: boolean;
}

/** La clave del grupo "Antes del seguimiento" en la historia. */
export const BEFORE_TRACKING = 'antes';

/**
 * Los avances del seguimiento: sin la carga inicial, que es lo que se hizo antes de empezar
 * a seguir la obra, con fechas aproximadas. No es "lo último" ni "lo de este mes".
 */
export function trackedEntries(entries: PublicProgressEntry[]): PublicProgressEntry[] {
  return entries.filter(entry => entry.source !== 'INITIAL_LOAD');
}

/** Nombre del grupo para las capturas sin sector, igual que en el recorrido. */
export const GENERAL_SECTOR = 'General';

/**
 * La historia agrupada por mes, del más nuevo al más viejo: los avances y los sectores con
 * recorrido 3D de cada mes. Un mes con recorridos y sin avances también aparece.
 */
export function historyMonths(
  entries: PublicProgressEntry[],
  captures: Pick<PublicCapture, 'date' | 'zone'>[],
  sectors: string[] = [],
): HistoryMonth[] {
  const months = new Map<string, HistoryMonth>();
  const monthOf = (key: string) => {
    let month = months.get(key);
    if (!month) {
      month = { month: key, entries: [], sectors: [] };
      months.set(key, month);
    }
    return month;
  };
  trackedEntries(entries)
    .map((entry, order) => ({ entry, order }))
    .sort((a, b) => b.entry.date.localeCompare(a.entry.date) || a.order - b.order)
    .forEach(({ entry }) => monthOf(entry.date.slice(0, 7)).entries.push(entry));

  const byMonth = new Map<string, Pick<PublicCapture, 'date' | 'zone'>[]>();
  for (const capture of captures) {
    const key = capture.date.slice(0, 7);
    byMonth.set(key, [...(byMonth.get(key) ?? []), capture]);
  }
  for (const [key, monthCaptures] of byMonth) {
    const named = zonesOf(monthCaptures as PublicCapture[], sectors);
    monthOf(key).sectors = monthCaptures.some(capture => !capture.zone) ? [...named, GENERAL_SECTOR] : named;
  }
  const tracked = [...months.values()].sort((a, b) => b.month.localeCompare(a.month));
  // La carga inicial va al final, en su propio grupo y en orden de obra.
  const initial = entries.filter(entry => entry.source === 'INITIAL_LOAD')
    .map((entry, order) => ({ entry, order }))
    .sort((a, b) => a.entry.date.localeCompare(b.entry.date) || a.order - b.order)
    .map(({ entry }) => entry);
  return initial.length === 0 ? tracked
    : [...tracked, { month: BEFORE_TRACKING, entries: initial, sectors: [], beforeTracking: true }];
}

/** "2026-10" a "Octubre 2026": el encabezado de un mes de la historia. */
export function monthHeading(month: string): string {
  const name = MONTHS_LONG[Number(month.slice(5, 7)) - 1];
  return `${name.charAt(0).toUpperCase()}${name.slice(1)} ${month.slice(0, 4)}`;
}

/** Los meses con al menos una captura, del más viejo al más nuevo ("2026-07"). */
export function captureMonths(captures: Pick<PublicCapture, 'date'>[]): string[] {
  return [...new Set(captures.map(capture => capture.date.slice(0, 7)))].sort();
}

/**
 * Lo que se ve de un sector en un mes: su captura más reciente con fecha hasta fin de ese
 * mes. Así un sector que no se volvió a grabar sigue mostrando su última captura, y no hay
 * que regrabar toda la casa cada mes. Con dos capturas en el mismo mes, la más nueva; con
 * la misma fecha, la que llegó después. Nula si el sector todavía no tenía captura.
 */
export function captureAtMonth<T extends Pick<PublicCapture, 'date'>>(sectorCaptures: T[], month: string): T | null {
  let shown: T | null = null;
  for (const capture of sectorCaptures) {
    if (capture.date.slice(0, 7) <= month && (shown === null || capture.date >= shown.date)) {
      shown = capture;
    }
  }
  return shown;
}

const MONTHS_LONG = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
  'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** "2026-07" a "jul": para decir de qué mes es lo que se ve. */
export function shortMonth(month: string): string {
  return SHORT_MONTHS[Number(month.slice(5, 7)) - 1];
}

/** "2026-07" a "Jul 2026": el punto de la línea de tiempo. */
export function monthStop(month: string): string {
  const name = shortMonth(month);
  return `${name.charAt(0).toUpperCase()}${name.slice(1)} ${month.slice(0, 4)}`;
}

/** "2026-10" a "octubre 2026": el mes de lo que se muestra de un lugar. */
export function monthAndYear(month: string): string {
  return `${MONTHS_LONG[Number(month.slice(5, 7)) - 1]} ${month.slice(0, 4)}`;
}

/** "2026-10" a "Octubre". */
export function monthName(month: string): string {
  const name = MONTHS_LONG[Number(month.slice(5, 7)) - 1];
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/** "2026-07" a "julio de 2026". */
export function longMonth(month: string): string {
  return `${MONTHS_LONG[Number(month.slice(5, 7)) - 1]} de ${month.slice(0, 4)}`;
}

/**
 * Ambientes con captura. Primero los sectores definidos para la casa, en su orden; después
 * los demás, en el orden en que aparecieron por primera vez.
 */
export function zonesOf(captures: PublicCapture[], sectors: string[] = []): string[] {
  const zones: string[] = [];
  for (const capture of captures) {
    if (capture.zone && !zones.includes(capture.zone)) {
      zones.push(capture.zone);
    }
  }
  const rank = (zone: string) => {
    const index = sectors.indexOf(zone);
    return index < 0 ? sectors.length : index;
  };
  return zones.map((zone, order) => ({ zone, order })).sort((a, b) => rank(a.zone) - rank(b.zone) || a.order - b.order).map(item => item.zone);
}

/**
 * Ambiente donde abrir el recorrido cuando no se pide una fecha: el que tiene más
 * recorridos, porque es donde se puede viajar en el tiempo; a igualdad, el más reciente.
 * Nulo si ningún recorrido tiene ambiente.
 */
export function busiestZone(captures: PublicCapture[]): string | null {
  const counts = new Map<string, { count: number; last: string }>();
  for (const capture of captures) {
    if (!capture.zone) {
      continue;
    }
    const current = counts.get(capture.zone) ?? { count: 0, last: '' };
    counts.set(capture.zone, { count: current.count + 1, last: capture.date > current.last ? capture.date : current.last });
  }
  let best: string | null = null;
  let bestValue = { count: 0, last: '' };
  for (const [zone, value] of counts) {
    if (value.count > bestValue.count || (value.count === bestValue.count && value.last > bestValue.last)) {
      best = zone;
      bestValue = value;
    }
  }
  return best;
}

/**
 * Cómo se nombra la propiedad. En una obra única la propiedad es la obra, así que el
 * título es su nombre; en un edificio es la unidad, dentro de la obra.
 */
export function propertyTitle(property: {
  developmentKind: DevelopmentKind; developmentName: string; unitCode: string;
}): string {
  return property.developmentKind === 'SINGLE' ? property.developmentName : property.unitCode;
}

/** Línea de contexto sobre el título: la dirección en una obra única, la obra en un edificio. */
export function propertyContext(property: {
  developmentKind: DevelopmentKind; developmentName: string; developmentAddress: string | null;
}): string | null {
  if (property.developmentKind === 'SINGLE') {
    return property.developmentAddress;
  }
  return property.developmentAddress
    ? property.developmentName + ' · ' + property.developmentAddress
    : property.developmentName;
}

// --- Vista de la familia: el camino de la casa, su ficha y sus momentos en 3D ---

export type JourneyState = 'DONE' | 'CURRENT' | 'UPCOMING';

export interface JourneyStep {
  stage: PublicStage;
  state: JourneyState;
  /** Cuándo, en palabras: "Terminó el 12 feb", "En curso desde el 16 ago", "Prevista para oct". */
  when: string | null;
  /** Demorada: lo dice el estado o ya pasó su fin previsto sin terminar. */
  late: boolean;
}

const LONG_MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
  'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** "2026-02-12" a "12 feb". Sin año: la obra de una casa dura menos de dos años y el año se lee en contexto. */
export function shortDate(iso: string): string {
  const [, month, day] = iso.split('-');
  return `${Number(day)} ${SHORT_MONTHS[Number(month) - 1]}`;
}

/** "2026-10-05" o "2026-10" a "octubre de 2026". */
export function monthYear(iso: string): string {
  const [year, month] = iso.split('-');
  return `${LONG_MONTHS[Number(month) - 1]} de ${year}`;
}

/**
 * Las etapas como un camino: lo terminado, lo que está en curso y lo que viene, con su
 * fecha real o prevista. Si nada está en curso, la primera pendiente es la actual: es la
 * que la familia espera que empiece.
 */
export function stageJourney(stages: PublicStage[], today: string): JourneyStep[] {
  const ordered = [...stages].sort((a, b) => a.orderIndex - b.orderIndex);
  const hasActive = ordered.some(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED');
  const firstPending = ordered.find(stage => stage.status === 'PENDING');
  return ordered.map(stage => {
    const active = stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED';
    const state: JourneyState = stage.status === 'COMPLETED'
      ? 'DONE'
      : active || (!hasActive && stage === firstPending) ? 'CURRENT' : 'UPCOMING';
    const late = stage.status === 'DELAYED'
      || (stage.status !== 'COMPLETED' && stage.plannedEnd !== null && stage.plannedEnd < today);
    let when: string | null = null;
    if (state === 'DONE' && stage.completedOn) {
      when = `Terminó el ${shortDate(stage.completedOn)}`;
    } else if (active && stage.startedOn) {
      when = `En curso desde el ${shortDate(stage.startedOn)}`;
    } else if (stage.plannedStart) {
      when = `Prevista para ${monthYear(stage.plannedStart)}`;
    }
    return { stage, state, when, late };
  });
}

/** "Etapa 8 de 12", contando la actual. Nulo sin etapas o con la obra terminada. */
export function stagePosition(stages: PublicStage[], today: string): { number: number; total: number } | null {
  const journey = stageJourney(stages, today);
  const index = journey.findIndex(step => step.state === 'CURRENT');
  return index < 0 ? null : { number: index + 1, total: journey.length };
}

export interface HouseFact {
  label: string;
  value: string;
}

const decimal = (value: number) => new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value);

/** Los datos principales de la casa, sólo los cargados, en el orden en que se lee una ficha. */
export function houseFacts(details: PublicUnitDetails): HouseFact[] {
  const facts: HouseFact[] = [];
  const covered = details.coveredAreaM2 ?? details.areaM2;
  if (covered !== null) facts.push({ label: 'Cubiertos', value: `${decimal(covered)} m²` });
  if (details.semiCoveredAreaM2 !== null) facts.push({ label: 'Semicubiertos', value: `${decimal(details.semiCoveredAreaM2)} m²` });
  if (details.lotAreaM2 !== null) facts.push({ label: 'Lote', value: `${decimal(details.lotAreaM2)} m²` });
  if (details.rooms !== null) facts.push({ label: 'Ambientes', value: String(details.rooms) });
  if (details.bedrooms !== null) facts.push({ label: details.bedrooms === 1 ? 'Dormitorio' : 'Dormitorios', value: String(details.bedrooms) });
  if (details.bathrooms !== null) facts.push({ label: details.bathrooms === 1 ? 'Baño' : 'Baños', value: String(details.bathrooms) });
  if (details.toilettes) facts.push({ label: details.toilettes === 1 ? 'Toilette' : 'Toilettes', value: String(details.toilettes) });
  if (details.floors !== null) facts.push({ label: details.floors === 1 ? 'Planta' : 'Plantas', value: String(details.floors) });
  if (details.parkingSpaces) facts.push({ label: details.parkingSpaces === 1 ? 'Cochera' : 'Cocheras', value: String(details.parkingSpaces) });
  if (details.orientation) facts.push({ label: 'Orientación', value: details.orientation });
  return facts;
}

export interface CaptureMoment {
  date: string;
  /** Recorridos de ese día: de la casa y de los espacios del barrio. */
  count: number;
  stageName: string | null;
}

/** Los días con recorrido 3D de la casa, del primero al último: la línea del viaje en el tiempo. */
export function captureMoments(captures: PublicCapture[]): CaptureMoment[] {
  const moments: CaptureMoment[] = [];
  for (const capture of [...captures].sort((a, b) => a.date.localeCompare(b.date))) {
    const last = moments[moments.length - 1];
    if (last?.date === capture.date) {
      last.count++;
    } else {
      // Un recorrido del barrio se nombra por el lugar: "Club house" dice más que el nombre de su etapa.
      moments.push({ date: capture.date, count: 1, stageName: capture.common ? capture.zone ?? capture.stageName : capture.stageName });
    }
  }
  return moments;
}
