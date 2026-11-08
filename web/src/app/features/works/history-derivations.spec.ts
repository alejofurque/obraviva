import { aMedia, aStage, anEntry } from '../../testing/fixtures';
import { filterEntries, historyTotals, photosWithoutPlace, stageFilters } from './history-derivations';

const entries = [
  anEntry({ id: 'a', rootId: 'a', stageId: 's2' }),
  anEntry({ id: 'b', rootId: 'b', stageId: 's2' }),
  anEntry({ id: 'c', rootId: 'c', stageId: 's1' }),
  anEntry({ id: 'd', rootId: 'd', stageId: null }),
];
const stages = [
  aStage({ id: 's2', name: 'Instalaciones', orderIndex: 2 }),
  aStage({ id: 's1', name: 'Estructura', orderIndex: 1 }),
  aStage({ id: 's3', name: 'Aberturas', orderIndex: 3 }),
];

describe('Historia: filtro y fotos sin lugar', () => {
  it('ofrece todas, las etapas con avances en el orden de la obra y General al final, con sus conteos', () => {
    expect(stageFilters(entries, stages, 'General de la casa')).toEqual([
      { key: 'all', name: 'Todas', count: 4 },
      { key: 's1', name: 'Estructura', count: 1 },
      { key: 's2', name: 'Instalaciones', count: 2 },
      { key: 'general', name: 'General de la casa', count: 1 },
    ]);
  });

  it('filtra por etapa, por los avances sin etapa y por los que tienen fotos sin lugar', () => {
    expect(filterEntries(entries, 's2').map(entry => entry.id)).toEqual(['a', 'b']);
    expect(filterEntries(entries, 'general').map(entry => entry.id)).toEqual(['d']);
    expect(filterEntries(entries, 'all', new Set(['b', 'd'])).map(entry => entry.id)).toEqual(['b', 'd']);
  });

  it('las fotos sin lugar son las de los avances que se muestran: no las de la galería ni las que ya tienen lugar', () => {
    const media = [
      aMedia({ id: 'm1', progressRootId: 'a', sector: null }),
      aMedia({ id: 'm2', progressRootId: 'a', sector: 'Cocina' }),
      aMedia({ id: 'm3', progressRootId: null, progressEntryId: null, showcase: true, sector: null }),
      aMedia({ id: 'm4', progressRootId: 'z', sector: null }),
      aMedia({ id: 'm5', progressRootId: 'b', sector: null, kind: 'VIDEO' }),
    ];
    expect(photosWithoutPlace(media, entries).map(asset => asset.id)).toEqual(['m1']);
    expect(historyTotals(entries, media)).toBe('4 avances · 2 fotos');
    expect(historyTotals([entries[0]], [])).toBe('1 avance');
  });
});
