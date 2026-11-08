import { ActivityItem, Capture3d, CaptureProcessing, ExtractionSummary, MediaAsset, Stage } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { SHORT_MONTHS } from '../../shared/time/short-date';

/** "Carlos Medina" a "Carlos": el saludo del encargado. */
export function firstName(fullName: string | null | undefined): string | null {
  const first = (fullName ?? '').trim().split(/\s+/)[0];
  return first ? first : null;
}

/** La etapa que se está trabajando: la en curso o demorada; si no hay, la primera pendiente. */
export function workingStage(stages: Stage[]): Stage | null {
  const ordered = [...stages].sort((a, b) => a.orderIndex - b.orderIndex);
  return ordered.find(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED')
    ?? ordered.find(stage => stage.status === 'PENDING')
    ?? null;
}

export type SectorState = 'DONE' | 'PROCESSING' | 'FAILED' | 'MISSING';

export interface SectorStatus {
  sector: string;
  state: SectorState;
  /** Por qué falló el procesamiento, tal como lo explica el backend; sólo en FAILED. */
  failureReason?: string | null;
}

/**
 * Qué sectores de una casa ya tienen recorrido en una fecha. Grabado si hay una captura
 * disponible, procesando si el video está en camino, falló si el procesamiento no salió,
 * y falta si no se subió nada. Un sector ya grabado no vuelve atrás por un reintento.
 */
export function sessionStatus(
  sectors: string[],
  available: Pick<Capture3d, 'unitId' | 'captureDate' | 'zone'>[],
  processing: (Pick<CaptureProcessing, 'unitId' | 'captureDate' | 'zone' | 'jobStatus'> & { failureReason?: string | null })[],
  unitId: string,
  date: string,
): SectorStatus[] {
  return sectors.map(sector => {
    const matches = <T extends { unitId: string | null; captureDate: string; zone: string | null }>(item: T) =>
      item.unitId === unitId && item.captureDate === date && item.zone === sector;
    if (available.some(matches)) {
      return { sector, state: 'DONE' as const };
    }
    const pending = processing.filter(matches);
    if (pending.some(item => item.jobStatus !== 'FAILED' && item.jobStatus !== 'UPLOADED')) {
      return { sector, state: 'PROCESSING' as const };
    }
    const failed = pending.find(item => item.jobStatus === 'FAILED');
    if (failed) {
      return { sector, state: 'FAILED' as const, failureReason: failed.failureReason ?? null };
    }
    return { sector, state: 'MISSING' as const };
  });
}

/** "3 de 4 sectores": cuenta lo grabado y lo que ya está en camino. */
export function sessionProgress(statuses: SectorStatus[]): { sent: number; total: number } {
  return {
    sent: statuses.filter(status => status.state === 'DONE' || status.state === 'PROCESSING').length,
    total: statuses.length,
  };
}

/** Un lugar de la casa en la sesión de un día: su recorrido 3D y sus fotos y videos. */
export interface SessionPlace extends SectorStatus {
  photos: number;
  videos: number;
  /** Si el lugar tuvo alguna vez un recorrido 3D, en cualquier fecha. */
  everHad3d: boolean;
}

/**
 * Lo que tiene cada lugar en la sesión del día: el estado de su recorrido 3D y cuántas fotos
 * y videos suyos son de esa fecha (los de la sesión, por su día, y los de un avance subidos
 * ese día). La galería de venta no cuenta: no es evidencia de obra.
 */
export function sessionPlaces(
  statuses: SectorStatus[],
  media: Pick<MediaAsset, 'unitId' | 'sector' | 'kind' | 'showcase' | 'takenOn' | 'createdAt'>[],
  captures: Pick<Capture3d, 'unitId' | 'zone'>[],
  processing: Pick<CaptureProcessing, 'unitId' | 'zone'>[],
  unitId: string,
  date: string,
): SessionPlace[] {
  return statuses.map(status => {
    const mine = media.filter(item => item.unitId === unitId && item.sector === status.sector && !item.showcase
      && (item.takenOn ?? todayLocal(new Date(item.createdAt))) === date);
    return {
      ...status,
      photos: mine.filter(item => item.kind === 'PHOTO').length,
      videos: mine.filter(item => item.kind === 'VIDEO').length,
      everHad3d: [...captures, ...processing].some(item => item.unitId === unitId && item.zone === status.sector),
    };
  });
}

/** "3 de 5 lugares con material": tiene material si su 3D está listo o en camino, o si tiene fotos o videos. */
export function sessionPlaceProgress(places: SessionPlace[]): { done: number; total: number } {
  return {
    done: places.filter(place => place.state === 'DONE' || place.state === 'PROCESSING' || place.photos + place.videos > 0).length,
    total: places.length,
  };
}

/**
 * Lo que dice la fila de un lugar: "3D listo · 3 fotos", "3D reconstruyendo · 12 min",
 * "4 fotos · sin 3D: se sigue con fotos" (si nunca tuvo un recorrido 3D) o "Falta".
 * `minutes` es cuánto lleva reconstruyéndose, si se sabe.
 */
export function sessionPlaceLine(place: SessionPlace, minutes: number | null = null): string {
  const parts: string[] = [];
  if (place.state === 'DONE') parts.push('3D listo');
  if (place.state === 'PROCESSING') parts.push(minutes === null ? 'Reconstruyendo…' : `3D reconstruyendo · ${durationText(minutes)}`);
  if (place.state === 'FAILED') parts.push(`3D falló: ${place.failureReason || 'no se pudo procesar'}`);
  if (place.photos > 0) parts.push(`${place.photos} ${place.photos === 1 ? 'foto' : 'fotos'}`);
  if (place.videos > 0) parts.push(`${place.videos} ${place.videos === 1 ? 'video' : 'videos'}`);
  if (place.state === 'MISSING' && place.photos + place.videos > 0 && !place.everHad3d) parts.push('sin 3D: se sigue con fotos');
  return parts.length > 0 ? parts.join(' · ') : 'Falta';
}

/** "12 min", "1 h 20 min". */
function durationText(minutes: number): string {
  const rounded = Math.max(0, Math.round(minutes));
  if (rounded < 60) return `${rounded} min`;
  const rest = rounded % 60;
  return rest === 0 ? `${Math.floor(rounded / 60)} h` : `${Math.floor(rounded / 60)} h ${rest} min`;
}

/** Si el lugar ya tiene material del día: su 3D listo o fotos y videos. Su tarjeta se pliega. */
export function placeHasMaterial(place: SessionPlace): boolean {
  return place.state === 'DONE' || place.photos + place.videos > 0;
}

/** El próximo lugar por recorrer: el primero, en el orden de la casa, sin nada del día ni en camino. */
export function nextPlace(places: SessionPlace[]): string | null {
  return places.find(place => !placeHasMaterial(place) && place.state !== 'PROCESSING')?.sector ?? null;
}

/** Cuántos minutos lleva reconstruyéndose el 3D de un lugar en una fecha; nulo si no se está reconstruyendo. */
export function reconstructionMinutes(
  processing: Pick<CaptureProcessing, 'unitId' | 'captureDate' | 'zone' | 'jobStatus' | 'createdAt'>[],
  unitId: string, date: string, sector: string, now: Date = new Date(),
): number | null {
  const item = processing.find(candidate => candidate.unitId === unitId && candidate.captureDate === date
    && candidate.zone === sector && candidate.jobStatus !== 'FAILED' && candidate.jobStatus !== 'UPLOADED');
  return item ? Math.max(0, (now.getTime() - new Date(item.createdAt).getTime()) / 60_000) : null;
}

/** "martes 27 oct": el día de la sesión en el encabezado del recorrido. */
export function sessionDayLine(day: string): string {
  const date = new Date(`${day}T12:00:00`);
  return `${WEEKDAYS[date.getDay()]} ${date.getDate()} ${SHORT_MONTHS[date.getMonth()]}`;
}

/** Los audios propios de una casa que esperan algo: los listos para revisar y los que se procesan. */
export interface AudioNotice {
  ready: ExtractionSummary[];
  processing: ExtractionSummary[];
}

/**
 * Lo que dice "Hoy" sobre los audios de la persona en la casa: los listos para revisar
 * primero el más viejo, que es el que más espera; los que todavía se transcriben aparte,
 * para poder volver a ellos.
 */
export function audioNotice(audios: ExtractionSummary[], unitId: string): AudioNotice {
  const own = audios.filter(audio => audio.mine && audio.unitId === unitId)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return {
    ready: own.filter(audio => audio.reviewStatus === 'READY_FOR_REVIEW'),
    processing: own.filter(audio => audio.reviewStatus === 'PENDING'),
  };
}

/** "Tenés 1 audio para revisar", "Tenés 3 audios para revisar". */
export function audioNoticeLine(count: number): string {
  return `Tenés ${count} ${count === 1 ? 'audio' : 'audios'} para revisar`;
}

const WEEKDAYS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
const MONTH_NAMES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre',
  'noviembre', 'diciembre'];

/** "Martes 27 de octubre": la fecha arriba del saludo, en la hora del teléfono. */
export function todayHeading(now: Date = new Date()): string {
  const text = `${WEEKDAYS[now.getDay()]} ${now.getDate()} de ${MONTH_NAMES[now.getMonth()]}`;
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Las etapas que se están trabajando (en curso o demoradas), en orden de obra. */
export function openStages(stages: Stage[]): Stage[] {
  return stages.filter(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED')
    .sort((a, b) => a.orderIndex - b.orderIndex);
}


/** "hoy", "ayer" o "14 oct": el día de un instante, en la hora del teléfono. */
export function shortWhen(at: string, today: string): string {
  const day = todayLocal(new Date(at));
  if (day === today) return 'hoy';
  const yesterday = new Date(`${today}T12:00:00`);
  yesterday.setDate(yesterday.getDate() - 1);
  if (day === todayLocal(yesterday)) return 'ayer';
  return `${Number(day.slice(8, 10))} ${SHORT_MONTHS[Number(day.slice(5, 7)) - 1]}`;
}

/**
 * "hoy · Instalación eléctrica": cuándo y qué fue lo último que publicó la persona. Un avance
 * dice su etapa (o "General de la casa"); unas fotos o un recorrido, eso.
 */
export function lastPublicationLine(item: ActivityItem, today: string): string {
  const what = item.entry
    ? item.entry.stageName ?? 'General de la casa'
    : item.session
      ? (item.session.photos === 1 ? '1 foto' : `${item.session.photos} fotos`)
      : 'Recorrido 3D';
  return `${shortWhen(item.at, today)} · ${what}`;
}

/** La fecha de un avance en "Publicar avance": "hoy, 27 oct", "ayer, 26 oct" o "14 oct". */
export function entryDateLine(day: string, today: string): string {
  const short = `${Number(day.slice(8, 10))} ${SHORT_MONTHS[Number(day.slice(5, 7)) - 1]}`;
  const when = shortWhen(`${day}T12:00:00`, today);
  return when === 'hoy' || when === 'ayer' ? `${when}, ${short}` : short;
}

/**
 * Las etapas que "Publicar avance" ofrece como chips: las que se están trabajando y, si la
 * elegida es otra (llegó con `?etapa=` o desde "Otra etapa…"), también esa, en orden de obra.
 */
export function stageChips(stages: Stage[], chosenId: string): Stage[] {
  const open = openStages(stages);
  const chosen = stages.find(stage => stage.id === chosenId);
  return chosen && !open.includes(chosen)
    ? [...open, chosen].sort((a, b) => a.orderIndex - b.orderIndex)
    : open;
}
