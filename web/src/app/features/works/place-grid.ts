import { Capture3d, CaptureProcessing, MediaAsset, ProgressEntry, SectorView } from '../../core/api/api.models';
import { localDay } from './activity-derivations';
import { shortMonth } from '../../shared/time/short-date';

/** El lugar de lo que no tiene sector, como lo nombra el comprador. */
export const GENERAL = 'General';

export interface PlaceCell {
  month: string;
  /** Recorridos 3D listos de ese lugar ese mes. */
  tours: Capture3d[];
  /** Los que se están reconstruyendo; los que fallaron van en "En proceso". */
  building: CaptureProcessing[];
  /** Fotos y videos de obra (avances y sesiones), de la más nueva a la más vieja. */
  media: MediaAsset[];
}

export interface PlaceRow {
  name: string;
  front: boolean;
  /** Una celda por mes de la grilla, en el mismo orden. */
  cells: PlaceCell[];
  tours: number;
  photos: number;
  /** La foto más nueva del lugar, para la miniatura; nula si sólo tiene 3D. */
  thumb: MediaAsset | null;
}

export interface PlaceGrid {
  /** De más viejo a más nuevo, "2026-07". */
  months: string[];
  rows: PlaceRow[];
}

const monthOf = (day: string) => day.slice(0, 7);

/** Del primer mes al último, sin saltos: un mes sin material también dice algo. */
function monthRange(months: string[]): string[] {
  if (months.length === 0) {
    return [];
  }
  const range: string[] = [];
  let [year, month] = months[0].split('-').map(Number);
  const last = months[months.length - 1];
  for (let current = months[0]; current <= last; current = `${year}-${String(month).padStart(2, '0')}`) {
    range.push(current);
    month++;
    if (month > 12) {
      month = 1;
      year++;
    }
  }
  return range;
}

/**
 * Qué se capturó de cada lugar, mes a mes: los recorridos listos, los que se reconstruyen y
 * cuántas fotos y videos hay. Es lo que el comprador recorre en "lugar por lugar" y en 3D,
 * con la misma regla de fecha (la del avance; si no, la de la sesión; si no, la de subida) y
 * sin la galería, que es material de venta. Los lugares van en el orden de la casa; después,
 * los que tienen material y ya no son sectores, y "General" al final si tiene algo.
 */
export function placeGrid(
  sectors: SectorView[], captures: Capture3d[], processing: CaptureProcessing[],
  media: MediaAsset[], entries: ProgressEntry[], unitId: string | null,
): PlaceGrid {
  const entryDay = new Map(entries.filter(entry => entry.current).map(entry => [entry.rootId, entry.entryDate]));
  const work = media.filter(asset => asset.unitId === unitId && !asset.showcase
    && (asset.progressRootId === null || entryDay.has(asset.progressRootId)));
  const dayOf = (asset: MediaAsset) =>
    (asset.progressRootId ? entryDay.get(asset.progressRootId) : null) ?? asset.takenOn ?? localDay(asset.createdAt);
  const tours = captures.filter(capture => capture.unitId === unitId && capture.status === 'AVAILABLE');
  const building = processing.filter(capture => capture.unitId === unitId
    && capture.jobStatus !== 'FAILED' && capture.jobStatus !== 'UPLOADED');

  const placeOf = (sector: string | null) => sector ?? GENERAL;
  const months = monthRange([...new Set([
    ...work.map(asset => monthOf(dayOf(asset))),
    ...tours.map(capture => monthOf(capture.captureDate)),
    ...building.map(capture => monthOf(capture.captureDate)),
  ])].sort());

  const names = sectors.map(sector => sector.name);
  const extra = [...new Set([
    ...work.map(asset => placeOf(asset.sector)),
    ...tours.map(capture => placeOf(capture.zone)),
    ...building.map(capture => placeOf(capture.zone)),
  ])].filter(name => !names.includes(name) && name !== GENERAL);
  const ordered = [...names, ...extra];
  const hasGeneral = work.some(asset => !asset.sector) || tours.some(capture => !capture.zone) || building.some(capture => !capture.zone);
  if (hasGeneral && !ordered.includes(GENERAL)) {
    ordered.push(GENERAL);
  }

  const rows = ordered.map(name => {
    const placeMedia = work.filter(asset => placeOf(asset.sector) === name)
      .sort((a, b) => dayOf(b).localeCompare(dayOf(a)) || b.createdAt.localeCompare(a.createdAt));
    const placeTours = tours.filter(capture => placeOf(capture.zone) === name);
    const placeBuilding = building.filter(capture => placeOf(capture.zone) === name);
    return {
      name,
      front: sectors.some(sector => sector.name === name && sector.front),
      cells: months.map(month => ({
        month,
        tours: placeTours.filter(capture => monthOf(capture.captureDate) === month),
        building: placeBuilding.filter(capture => monthOf(capture.captureDate) === month),
        media: placeMedia.filter(asset => monthOf(dayOf(asset)) === month),
      })),
      tours: placeTours.length,
      photos: placeMedia.length,
      thumb: placeMedia.find(asset => asset.kind === 'PHOTO') ?? null,
    };
  });
  return { months, rows };
}

/** "2 recorridos · 5 fotos", "Sólo fotos · 9", "Sin material todavía". */
export function placeMeta(row: Pick<PlaceRow, 'tours' | 'photos'>): string {
  const photos = row.photos === 1 ? '1 foto' : `${row.photos} fotos`;
  if (row.tours === 0) {
    return row.photos === 0 ? 'Sin material todavía' : `Sólo fotos · ${row.photos}`;
  }
  const tours = row.tours === 1 ? '1 recorrido' : `${row.tours} recorridos`;
  return row.photos === 0 ? tours : `${tours} · ${photos}`;
}

/** El mes más nuevo con material de un lugar: la celda que se abre al entrar. */
export function latestCell(row: PlaceRow): PlaceCell | null {
  return [...row.cells].reverse().find(cell => cell.tours.length > 0 || cell.building.length > 0 || cell.media.length > 0) ?? null;
}

const LONG_MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre',
  'octubre', 'noviembre', 'diciembre'];

/** "2026-10" a "Oct 26", para la cabecera de la grilla. */
export function monthHead(month: string): string {
  return `${shortMonth(Number(month.slice(5, 7)) - 1, true)} ${month.slice(2, 4)}`;
}

/** "2026-10" a "octubre 2026". */
export function monthLong(month: string): string {
  return `${LONG_MONTHS[Number(month.slice(5, 7)) - 1]} ${month.slice(0, 4)}`;
}
