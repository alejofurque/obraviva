import { ActivityEntry, ActivityItem, ActivitySession } from '../../core/api/api.models';
import { STAGE_STATUS_LABELS } from '../../shared/ui/status-badge.component';
import { todayLocal } from '../../shared/time/local-date';
import { firstName } from '../field/field-derivations';

/**
 * Lecturas de las novedades: funciones puras sobre lo que manda el backend. El feed ya viene
 * ordenado del más nuevo al más viejo; acá se agrupa por día y se arma cada línea.
 */

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
  'septiembre', 'octubre', 'noviembre', 'diciembre'];

export interface ActivityDay {
  /** yyyy-mm-dd en la hora del navegador. */
  day: string;
  label: string;
  items: ActivityItem[];
}

/** El día de un instante en la hora del navegador: un avance de las 22 h es de ese día, no del siguiente. */
export function localDay(at: string): string {
  return todayLocal(new Date(at));
}

/** "Hoy", "Ayer", "15 de octubre"; con el año si no es el de hoy. */
export function dayLabel(day: string, today: string): string {
  if (day === today) return 'Hoy';
  const yesterday = new Date(`${today}T12:00:00`);
  yesterday.setDate(yesterday.getDate() - 1);
  if (day === todayLocal(yesterday)) return 'Ayer';
  const [year, month, date] = day.split('-');
  const label = `${Number(date)} de ${MONTHS[Number(month) - 1]}`;
  return year === today.slice(0, 4) ? label : `${label} de ${year}`;
}

/** Agrupa por día respetando el orden del feed. Una página nueva se suma a los días que ya hay. */
export function activityDays(items: ActivityItem[], today: string): ActivityDay[] {
  const days: ActivityDay[] = [];
  for (const item of items) {
    const day = localDay(item.at);
    const last = days.at(-1);
    if (last?.day === day) {
      last.items.push(item);
    } else {
      days.push({ day, label: dayLabel(day, today), items: [item] });
    }
  }
  return days;
}

/**
 * Lo que el avance le hizo a su etapa, en una o dos líneas: "Instalación eléctrica 35% → 50%"
 * y, si cambió el estado, "En ejecución → Completado". Nada si no la tocó o si es anterior a
 * que esto se guardara.
 */
export function stageChangeLines(entry: ActivityEntry): string[] {
  const change = entry.stageChange;
  if (!change) return [];
  const lines: string[] = [];
  if (change.percentBefore !== null && change.percentAfter !== null && change.percentBefore !== change.percentAfter) {
    lines.push(`${entry.stageName ?? 'Etapa'} ${change.percentBefore}% → ${change.percentAfter}%`);
  }
  if (change.statusBefore && change.statusAfter && change.statusBefore !== change.statusAfter) {
    lines.push(`${STAGE_STATUS_LABELS[change.statusBefore]} → ${STAGE_STATUS_LABELS[change.statusAfter]}`);
  }
  return lines;
}

/** "Carlos subió 4 fotos y 1 video · Frente, Living". */
export function sessionLine(item: ActivityItem, session: ActivitySession): string {
  const parts: string[] = [];
  if (session.photos > 0) parts.push(`${session.photos} ${session.photos === 1 ? 'foto' : 'fotos'}`);
  if (session.videos > 0) parts.push(`${session.videos} ${session.videos === 1 ? 'video' : 'videos'}`);
  const who = firstName(item.authorName) ?? 'Alguien';
  const places = session.sectors.length > 0 ? ` · ${session.sectors.join(', ')}` : '';
  return `${who} subió ${parts.join(' y ')}${places}`;
}

export interface ActivityLinks {
  view: string[];
  correct: { link: string[]; query: Record<string, string> | null } | null;
}

/**
 * Adónde lleva cada ítem, reusando lo que ya existe: un avance se corrige en Historia; las
 * fotos de sesión cambian de lugar o se borran en Recorridos 3D; un recorrido se edita ahí.
 */
/**
 * Lo mismo desde el trabajo de campo del encargado, sin salir de su marco: "Ver en la casa" va
 * a La casa de Hoy y "Corregir" un avance abre Publicar avance en modo corrección. Las fotos
 * de sesión se cambian de lugar en Lugares y 3D, que no es del campo: ahí no hay "Corregir".
 * Lo de las obras comunes (sin casa) sigue yendo al espacio de la obra.
 */
export function fieldActivityLinks(item: ActivityItem, developmentId: string): ActivityLinks {
  if (!item.unitId) {
    return activityLinks(item, developmentId);
  }
  const house = ['/hoy', item.unitId, 'casa'];
  return item.kind === 'ENTRY'
    ? { view: house, correct: { link: ['/hoy', item.unitId, 'avance', item.entry!.entryId], query: null } }
    : { view: house, correct: null };
}

export function activityLinks(item: ActivityItem, developmentId: string): ActivityLinks {
  const place = item.unitId ? ['/obras', developmentId, 'casas', item.unitId] : ['/obras', developmentId, 'comunes'];
  switch (item.kind) {
    case 'ENTRY':
      return {
        view: [...place, 'historia'],
        correct: { link: [...place, 'historia'], query: { corregir: item.entry!.entryId } },
      };
    case 'PHOTOS':
      return { view: place, correct: { link: [...place, 'recorridos'], query: null } };
    case 'SUPPLY':
      return { view: place, correct: null };
    default:
      return { view: [...place, 'recorridos'], correct: null };
  }
}
