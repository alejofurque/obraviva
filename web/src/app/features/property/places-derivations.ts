import { PublicFront, PublicMedia, PublicPlace, PublicPlaceMonth } from '../../core/api/api.models';
import {  } from '../../shared/media/variant.pipe';

/**
 * Lecturas de la casa lugar por lugar y de su frente. Funciones puras sobre lo que manda el
 * backend: qué foto va en la portada y qué se compara lo decide el servidor (nunca la
 * galería); acá sólo se ordena y se elige qué ofrecer.
 */

/** La portada viva: la última foto del frente, con su lugar y su día. Nula si no hay. */
export interface LiveCover {
  url: string;
  date: string;
  sector: string;
}

export function liveCover(front: PublicFront | null): LiveCover | null {
  // La portada es grande: la versión de pantalla, o el original si todavía no está.
  return front?.latest && front.sector
    ? { url: front.latest.screenUrl ?? front.latest.url, date: front.latest.date, sector: front.sector }
    : null;
}

/**
 * Los lugares que tienen algo que mostrar: fotos, videos o un recorrido 3D en algún mes. El
 * servidor ya manda sólo esos; acá se asegura, porque un chip que abre un lugar vacío
 * ("General" sin nada) es peor que no tenerlo.
 */
export function placesWithMaterial(places: PublicPlace[]): PublicPlace[] {
  return places.filter(place => place.months.some(month => month.tour || month.photos.length + month.videos.length > 0));
}

/** Los meses de un lugar, del más viejo al más nuevo, como una línea de tiempo. */
export function monthsChronological(place: PublicPlace): PublicPlaceMonth[] {
  return [...place.months].sort((a, b) => a.month.localeCompare(b.month));
}

/** "2 fotos · 1 video", lo que hay en un mes de un lugar. */
export function mediaCount(month: Pick<PublicPlaceMonth, 'photos' | 'videos'>): string {
  const parts: string[] = [];
  if (month.photos.length > 0) parts.push(`${month.photos.length} ${month.photos.length === 1 ? 'foto' : 'fotos'}`);
  if (month.videos.length > 0) parts.push(`${month.videos.length} ${month.videos.length === 1 ? 'video' : 'videos'}`);
  return parts.length > 0 ? parts.join(' · ') : 'Sin fotos ni videos';
}

/**
 * Qué decir del 3D de un lugar en un mes. Si lo hubo, se ofrece recorrerlo. Si no, pero el
 * lugar tuvo recorridos, se avisa y se ofrece el anterior más cercano (nulo si todos son
 * posteriores). Si nunca tuvo, es un lugar que se sigue con fotos y videos: derivado, no
 * una marca que alguien tenga que mantener.
 */
export type PlaceTour =
  | { kind: 'tour' }
  | { kind: 'missed'; previous: string | null }
  | { kind: 'photos-only' };

export function placeTour(place: PublicPlace, month: string): PlaceTour {
  if (place.months.some(item => item.month === month && item.tour)) {
    return { kind: 'tour' };
  }
  const withTour = place.months.filter(item => item.tour).map(item => item.month).sort();
  if (withTour.length === 0) {
    return { kind: 'photos-only' };
  }
  return { kind: 'missed', previous: withTour.filter(item => item < month).at(-1) ?? null };
}

/** El día que muestra una foto o video: el del avance, el de la sesión o el de la subida. */
export function mediaDay(media: PublicMedia): string {
  return media.entryDate ?? media.takenOn ?? media.uploadedAt.slice(0, 10);
}

/** Lo de un lugar en un mes, para la hoja del visor. Nulo si ese mes el lugar no tiene fotos ni videos. */
export function placeMediaAt(places: PublicPlace[], sector: string, month: string): PublicPlaceMonth | null {
  const found = places.find(place => place.name === sector)?.months.find(item => item.month === month) ?? null;
  return found && found.photos.length + found.videos.length > 0 ? found : null;
}

/** Una comparación de "Tu casa, de frente": qué va antes del corte y qué foto va después. */
export interface FrontOption {
  key: 'project' | 'start';
  label: string;
  beforeUrl: string;
  /** Día de la imagen de antes; nulo para el render. */
  beforeDate: string | null;
  afterUrl: string;
  afterDate: string;
}

/**
 * Las opciones de "Tu casa, de frente", las que haya. "El proyecto" es lo que decidió el
 * servidor (el render del frente contra la foto de hoy, o la comparación vieja de la casa);
 * "Cuando empezó", la primera foto del frente contra la de hoy, si son de meses distintos.
 */
export function frontOptions(front: PublicFront | null): FrontOption[] {
  const options: FrontOption[] = [];
  if (front?.project) {
    options.push({
      key: 'project', label: 'El proyecto', beforeUrl: front.project.renderScreenUrl ?? front.project.renderUrl, beforeDate: null,
      afterUrl: front.project.photoScreenUrl ?? front.project.photoUrl, afterDate: front.project.photoDate,
    });
  }
  if (front?.first && front.latest) {
    options.push({
      key: 'start', label: 'Cuando empezó', beforeUrl: front.first.screenUrl ?? front.first.url, beforeDate: front.first.date,
      afterUrl: front.latest.screenUrl ?? front.latest.url, afterDate: front.latest.date,
    });
  }
  return options;
}

/** Lo que puede faltar para el frente, en el orden en que se carga. */
export type FrontPiece = 'front' | 'photos' | 'render';

/**
 * Qué falta para "Tu casa, de frente" con el render del frente contra la foto de hoy. Vacío
 * si la sección ya tiene algo que mostrar: el aviso es para cuando el comprador no ve nada.
 */
export function frontMissing(front: PublicFront | null): FrontPiece[] {
  if (frontOptions(front).length > 0) {
    return [];
  }
  return [...coverMissing(front), ...(front?.renderUrl ? [] : ['render' as const])];
}

/** Qué falta para la portada viva: marcar el frente o, marcado, sus fotos. Vacío si la hay. */
export function coverMissing(front: PublicFront | null): FrontPiece[] {
  if (front?.latest) {
    return [];
  }
  return front?.sector ? ['photos'] : ['front'];
}
