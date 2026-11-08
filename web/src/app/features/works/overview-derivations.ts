import {
  ActivityEntry, ActivityItem, AlertStage, HouseCard, PendingAudio, PublicPlace, ShareLinkSummary, Stage, Unit,
} from '../../core/api/api.models';
import { STAGE_STATUS_LABELS } from '../../shared/ui/status-badge.component';
import { todayLocal } from '../../shared/time/local-date';
import { firstName } from '../field/field-derivations';
import { mediaDay, monthsChronological, placesWithMaterial } from '../property/places-derivations';
import { dayLabel, localDay } from './activity-derivations';
import { SHORT_MONTHS } from '../../shared/time/short-date';
import { hasDelay } from '../../shared/ui/stage-status';

/**
 * Lecturas de los resúmenes del dueño (del desarrollo y de la casa) y del formato compacto de
 * las novedades. Funciones puras sobre lo que manda el backend: acá no se calcula ninguna
 * cifra nueva, sólo se elige qué decir y cómo.
 */

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
  'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** Quién y qué, para una línea: "Carlos Medina" + "publicó un avance en Revoques". */
export interface ActivityLine {
  who: string;
  what: string;
}

export function activityLine(item: ActivityItem): ActivityLine {
  const who = item.authorName ?? 'Alguien';
  switch (item.kind) {
    case 'ENTRY': {
      const stage = item.entry?.stageName;
      return { who, what: stage ? `publicó un avance en ${stage}` : 'publicó un avance' };
    }
    case 'PHOTOS': {
      const session = item.session!;
      const parts: string[] = [];
      if (session.photos > 0) parts.push(`${session.photos} ${session.photos === 1 ? 'foto' : 'fotos'}`);
      if (session.videos > 0) parts.push(`${session.videos} ${session.videos === 1 ? 'video' : 'videos'}`);
      return { who, what: `subió ${parts.join(' y ')}${placesPhrase(session.sectors)}` };
    }
    case 'SUPPLY': {
      const [first, ...rest] = item.supply!.alerts;
      const more = rest.length === 0 ? '' : ` y ${rest.length} ${rest.length === 1 ? 'aviso' : 'avisos'} más`;
      return { who, what: `avisó: ${lowerFirst(first ?? 'un faltante de materiales')}${more}` };
    }
    default: {
      const capture = item.capture!;
      const state = capture.state === 'READY' ? 'listo' : capture.state === 'FAILED' ? 'falló' : 'reconstruyéndose';
      return { who: 'Recorrido 3D', what: `de ${capture.zone ?? 'General'} ${state}` };
    }
  }
}

/** "Falta el tablero" → "falta el tablero", para seguir la frase; una sigla ("PVC") queda igual. */
function lowerFirst(text: string): string {
  return /^[A-ZÁÉÍÓÚÑ][a-záéíóúñ]/.test(text) ? text.charAt(0).toLowerCase() + text.slice(1) : text;
}

/** " de Frente, Living y 4 lugares más". */
function placesPhrase(sectors: string[]): string {
  if (sectors.length === 0) return '';
  if (sectors.length <= 2) return ` de ${sectors.join(' y ')}`;
  const rest = sectors.length - 2;
  return ` de ${sectors.slice(0, 2).join(', ')} y ${rest} ${rest === 1 ? 'lugar' : 'lugares'} más`;
}

/** El cambio que un avance le hizo a su etapa, para el chip: "35% → 50%", "En ejecución → Demorado". */
export function changeChip(entry: Pick<ActivityEntry, 'stageChange'> | null): string | null {
  const change = entry?.stageChange;
  if (!change) return null;
  const parts: string[] = [];
  if (change.statusBefore && change.statusAfter && change.statusBefore !== change.statusAfter) {
    parts.push(`${STAGE_STATUS_LABELS[change.statusBefore]} → ${STAGE_STATUS_LABELS[change.statusAfter]}`);
  }
  if (change.percentBefore !== null && change.percentAfter !== null && change.percentBefore !== change.percentAfter) {
    parts.push(`${change.percentBefore}% → ${change.percentAfter}%`);
  }
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** "hoy", "ayer", "hace 3 días" en la semana; después, "15 de octubre". */
export function relativeWhen(at: string, today: string = todayLocal()): string {
  const day = localDay(at);
  const days = Math.round((Date.parse(`${today}T12:00:00`) - Date.parse(`${day}T12:00:00`)) / 86_400_000);
  if (days <= 0) return 'hoy';
  if (days === 1) return 'ayer';
  if (days < 7) return `hace ${days} días`;
  return dayLabel(day, today);
}

/** Las iniciales de quien publicó, para el avatar: "CM"; "3D" para un recorrido. */
export function initialsOf(item: ActivityItem): string {
  if (item.kind === 'CAPTURE') return '3D';
  const words = (item.authorName ?? '').trim().split(/\s+/).filter(Boolean);
  return words.slice(0, 2).map(word => word[0].toUpperCase()).join('') || '·';
}

/** Un monto para un indicador, en la moneda del sistema: "US$ 41,2 M". */
export { compactMoney } from '../../shared/money/money';

/** "Jul 2027" para un año-mes. */
export function shortYearMonth(value: string | null): string | null {
  if (!value) return null;
  const [year, month] = value.split('-');
  const name = SHORT_MONTHS[Number(month) - 1];
  return `${name[0].toUpperCase()}${name.slice(1)} ${year}`;
}

/** Cuánto falta para la entrega: "Faltan 9 meses", "Es este mes", "La fecha ya pasó". */
export function deliveryLine(yearMonth: string | null, today: string = todayLocal()): string {
  if (!yearMonth) return 'Sin fecha definida';
  const [year, month] = yearMonth.split('-').map(Number);
  const [nowYear, nowMonth] = today.split('-').map(Number);
  const months = (year - nowYear) * 12 + (month - nowMonth);
  if (months < 0) return 'La fecha ya pasó';
  if (months === 0) return 'Es este mes';
  return months === 1 ? 'Falta 1 mes' : `Faltan ${months} meses`;
}

/**
 * La etapa de la tarjeta de una casa: "Terminada", "Sin empezar · inicia en noviembre", o la
 * etapa en curso principal con "+2" si se trabajan más.
 */
export function cardStageLine(unit: Pick<Unit, 'progressPercent' | 'stageCount' | 'currentStageName'>, card: HouseCard | null): string {
  if (!unit.stageCount) return 'Sin etapas cargadas';
  const percent = unit.progressPercent ?? 0;
  if (percent >= 100) return 'Terminada';
  if (percent === 0 && !(card?.currentStageName && (card.otherRunningStages ?? 0) > 0)) {
    const start = card?.plannedStart;
    return start ? `Sin empezar · inicia en ${MONTHS[Number(start.slice(5, 7)) - 1]}` : 'Sin empezar';
  }
  const stage = card?.currentStageName ?? unit.currentStageName ?? 'Sin etapa en curso';
  const more = card?.otherRunningStages ?? 0;
  return more > 0 ? `${stage} +${more}` : stage;
}

/** El pie de la tarjeta: lo último que pasó, o por qué no pasa nada. */
export function houseActivityLine(card: HouseCard | null, today: string = todayLocal()): string {
  const last = card?.lastActivity;
  if (last) {
    const line = activityLine(last);
    const who = last.kind === 'CAPTURE' ? line.who : (firstName(last.authorName) ?? line.who);
    return `${who} ${line.what} · ${relativeWhen(last.at, today)}`;
  }
  return card?.hasSupervisor === false ? 'Sin encargado asignado' : 'Todavía sin novedades';
}

/** Un ítem de "Para revisar": un audio que espera o una etapa en alerta de gasto. */
export interface ReviewItem {
  kind: 'audio' | 'alert';
  title: string;
  detail: string;
  link: string[];
  action: string;
}

/** Audios primero (esperan a una persona), después las alertas en el orden del servidor: rojas primero. */
export function reviewItems(developmentId: string, audios: PendingAudio[], alerts: AlertStage[]): ReviewItem[] {
  const place = (unitId: string | null) => unitId ? ['/obras', developmentId, 'casas', unitId] : ['/obras', developmentId, 'comunes'];
  return [
    ...audios.map(audio => ({
      kind: 'audio' as const,
      title: `Audio de ${firstName(audio.authorName) ?? 'alguien'} · ${audio.unitCode ?? 'Obras comunes'}`,
      detail: proposalLine(audio.progressNotes ?? 0, audio.stageChanges ?? 0),
      link: [...place(audio.unitId), 'historia', 'audios', audio.extractionId],
      action: 'Revisar',
    })),
    ...alerts.map(alert => ({
      kind: 'alert' as const,
      title: `${alert.unitCode ?? 'Obras comunes'} · ${alert.name}`,
      detail: alert.budgetUsedPercent !== null && alert.progressPercent !== null
        ? `Gastó ${Math.round(alert.budgetUsedPercent)}% del presupuesto con ${alert.progressPercent}% de avance`
        : 'El gasto va por delante del avance',
      link: alert.unitId ? ['/obras', developmentId, 'casas', alert.unitId, 'economico'] : ['/obras', developmentId, 'economico'],
      action: 'Ver',
    })),
  ];
}

/** "2 novedades, 1 cambio de etapa". */
export function proposalLine(notes: number, changes: number): string {
  const parts: string[] = [];
  if (notes > 0) parts.push(`${notes} ${notes === 1 ? 'novedad' : 'novedades'}`);
  if (changes > 0) parts.push(`${changes} ${changes === 1 ? 'cambio de etapa' : 'cambios de etapa'}`);
  return parts.length > 0 ? parts.join(', ') : 'Sin cambios propuestos';
}

/**
 * Cómo van las etapas de una casa: terminadas, en curso, con demora (declarada o por haber
 * pasado su fin previsto, `hasDelay`) y la próxima.
 */
export function stageSummary(stages: Stage[], today: string): { done: number; total: number; running: number; delayed: number; next: string | null } {
  const ordered = [...stages].sort((a, b) => a.orderIndex - b.orderIndex);
  return {
    done: ordered.filter(stage => stage.status === 'COMPLETED').length,
    total: ordered.length,
    running: ordered.filter(stage => stage.status === 'IN_PROGRESS' || stage.status === 'DELAYED').length,
    delayed: ordered.filter(stage => hasDelay(stage, today)).length,
    next: ordered.find(stage => stage.status === 'PENDING')?.name ?? null,
  };
}

/** "En curso desde el 8 oct · fin previsto dic". */
export function stageMeta(stage: Pick<Stage, 'startedOn' | 'plannedEnd' | 'status'>): string {
  const parts: string[] = [];
  if (stage.startedOn) parts.push(`${stage.status === 'DELAYED' ? 'Demorada' : 'En curso'} desde el ${shortDay(stage.startedOn)}`);
  if (stage.plannedEnd) parts.push(`fin previsto ${SHORT_MONTHS[Number(stage.plannedEnd.slice(5, 7)) - 1]}`);
  return parts.join(' · ') || (stage.status === 'DELAYED' ? 'Demorada' : 'En curso');
}

/** "20 oct". */
export function shortDay(day: string): string {
  return `${Number(day.slice(8, 10))} ${SHORT_MONTHS[Number(day.slice(5, 7)) - 1]}`;
}

/** Un lugar de la casa en el Resumen: su miniatura más nueva, si tuvo 3D y qué tiene en su último mes. */
export interface PlaceTile {
  name: string;
  thumb: { url?: string | null; thumbUrl: string | null; screenUrl: string | null } | null;
  has3d: boolean;
  meta: string;
}

export function placeTiles(places: PublicPlace[]): PlaceTile[] {
  return placesWithMaterial(places).map(place => {
    const months = monthsChronological(place);
    const latest = months.at(-1)!;
    const withPhoto = [...months].reverse().find(month => month.photos.length > 0);
    const kinds = [latest.tour ? '3D' : null, latest.photos.length + latest.videos.length > 0 ? 'fotos' : null].filter(Boolean);
    const media = [...latest.photos, ...latest.videos].map(mediaDay).sort();
    const when = media.length > 0 ? shortDay(media.at(-1)!) : `${SHORT_MONTHS[Number(latest.month.slice(5, 7)) - 1]} ${latest.month.slice(0, 4)}`;
    const what = kinds.join(' y ');
    return {
      name: place.name,
      thumb: withPhoto ? withPhoto.photos.at(-1)! : null,
      has3d: months.some(month => month.tour),
      meta: `${what.charAt(0).toUpperCase()}${what.slice(1)} · ${when}`,
    };
  });
}

/** La tarjeta del comprador: sus nombres, o que no hay; los enlaces activos y su último acceso. */
export function buyerLines(members: { fullName: string | null; email: string | null }[], links: ShareLinkSummary[],
                           today: string = todayLocal()): { name: string; detail: string } {
  const names = members.map(member => member.fullName ?? member.email ?? 'Comprador');
  const active = links.filter(link => link.active);
  const lastAccess = active.map(link => link.lastAccessedAt).filter((at): at is string => !!at).sort().at(-1) ?? null;
  const linkPart = active.length === 0 ? 'Sin enlaces activos'
    : `${active.length} ${active.length === 1 ? 'enlace activo' : 'enlaces activos'}`;
  return {
    name: names.length === 0 ? 'Sin comprador' : names.length === 1 ? names[0] : `${names[0]} y ${names.length - 1} más`,
    detail: lastAccess ? `${linkPart} · último acceso ${relativeWhen(lastAccess, today)}` : linkPart,
  };
}
