import { MediaAsset, ProgressEntry, Stage } from '../../core/api/api.models';

/** "Todas", una etapa por su id, o los avances sin etapa ("General de la casa"). */
export type HistoryFilter = 'all' | 'general' | string;

export interface StageFilterOption {
  key: HistoryFilter;
  name: string;
  count: number;
}

/**
 * Las opciones de "Filtrar por etapa": todas, las etapas que tienen avances en el orden de la
 * obra, y "General" al final si hay avances sin etapa. Una etapa sin avances no se ofrece.
 */
export function stageFilters(entries: ProgressEntry[], stages: Stage[], generalLabel: string): StageFilterOption[] {
  const counts = new Map<string, number>();
  let general = 0;
  for (const entry of entries) {
    if (entry.stageId) {
      counts.set(entry.stageId, (counts.get(entry.stageId) ?? 0) + 1);
    } else {
      general++;
    }
  }
  const options: StageFilterOption[] = [{ key: 'all', name: 'Todas', count: entries.length }];
  for (const stage of [...stages].sort((a, b) => a.orderIndex - b.orderIndex)) {
    const count = counts.get(stage.id);
    if (count) {
      options.push({ key: stage.id, name: stage.name, count });
    }
  }
  if (general > 0) {
    options.push({ key: 'general', name: generalLabel, count: general });
  }
  return options;
}

export function filterEntries(entries: ProgressEntry[], filter: HistoryFilter, unplacedRoots: ReadonlySet<string> | null = null): ProgressEntry[] {
  return entries.filter(entry =>
    (filter === 'all' || (filter === 'general' ? entry.stageId === null : entry.stageId === filter))
    && (unplacedRoots === null || unplacedRoots.has(entry.rootId)));
}

/**
 * Las fotos de los avances que no tienen lugar. El comprador las ve en "General" de lugar por
 * lugar; con su lugar aparecen donde corresponde. Las de sesión siempre lo tienen y la galería
 * es aparte.
 */
export function photosWithoutPlace(media: MediaAsset[], entries: ProgressEntry[]): MediaAsset[] {
  const roots = new Set(entries.map(entry => entry.rootId));
  return media.filter(asset => asset.kind === 'PHOTO' && !asset.showcase && asset.sector === null
    && asset.progressRootId !== null && roots.has(asset.progressRootId));
}

/** "48 fotos", "1 foto": las de los avances que se muestran. */
export function historyTotals(entries: ProgressEntry[], media: MediaAsset[]): string {
  const roots = new Set(entries.map(entry => entry.rootId));
  const photos = media.filter(asset => asset.kind === 'PHOTO' && asset.progressRootId !== null && roots.has(asset.progressRootId)).length;
  const parts = [`${entries.length} ${entries.length === 1 ? 'avance' : 'avances'}`];
  if (photos > 0) {
    parts.push(`${photos} ${photos === 1 ? 'foto' : 'fotos'}`);
  }
  return parts.join(' · ');
}
