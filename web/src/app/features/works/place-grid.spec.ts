import { Capture3d, CaptureProcessing } from '../../core/api/api.models';
import { aMedia, anEntry } from '../../testing/fixtures';
import { latestCell, monthHead, monthLong, placeGrid, placeMeta } from './place-grid';

function capture(id: string, zone: string | null, captureDate: string): Capture3d {
  return {
    id, developmentId: 'd1', unitId: 'u1', stageId: null, captureDate, description: null, zone, status: 'AVAILABLE',
    transform: {} as Capture3d['transform'], splatUrl: null,
  };
}

function building(zone: string, captureDate: string, jobStatus: CaptureProcessing['jobStatus'] = 'PROCESSING'): CaptureProcessing {
  return { captureId: 'p-' + zone, unitId: 'u1', stageId: null, captureDate, description: null, zone, createdAt: captureDate + 'T10:00:00Z',
    jobStatus, pipelineStep: 'AWAIT_EXTERNAL' } as CaptureProcessing;
}

const sectors = [{ name: 'Frente', front: true }, { name: 'Living', front: false }, { name: 'Baño', front: false }];

describe('Grilla de lugares por mes', () => {
  it('cruza cada lugar con cada mes: recorridos listos, los que se reconstruyen y las fotos de obra', () => {
    const grid = placeGrid(sectors,
      [capture('c1', 'Living', '2026-07-05'), capture('c2', 'Living', '2026-10-05')],
      [building('Living', '2026-10-20'), building('Baño', '2026-10-21', 'FAILED')],
      [
        aMedia({ id: 'f1', unitId: 'u1', sector: 'Frente', progressEntryId: null, progressRootId: null, takenOn: '2026-08-02' }),
        aMedia({ id: 'f2', unitId: 'u1', sector: 'Frente', progressRootId: 'e1', progressEntryId: 'e1' }),
        aMedia({ id: 'g1', unitId: 'u1', sector: null, showcase: true, progressEntryId: null, progressRootId: null }),
        aMedia({ id: 'x1', unitId: 'u2', sector: 'Frente', progressEntryId: null, progressRootId: null, takenOn: '2026-08-02' }),
      ],
      [anEntry({ rootId: 'e1', entryDate: '2026-10-14' })], 'u1');

    expect(grid.months).toEqual(['2026-07', '2026-08', '2026-09', '2026-10']);
    expect(grid.rows.map(row => row.name)).toEqual(['Frente', 'Living', 'Baño']);
    const [front, living, bath] = grid.rows;
    expect(front.front).toBe(true);
    expect(front.cells.map(cell => cell.media.map(asset => asset.id))).toEqual([[], ['f1'], [], ['f2']]);
    expect(front.thumb?.id).toBe('f2');
    expect(living.cells.map(cell => cell.tours.length)).toEqual([1, 0, 0, 1]);
    expect(living.cells[3].building).toHaveLength(1);
    expect(bath.cells.every(cell => cell.building.length === 0)).toBe(true);
    expect(placeMeta(front)).toBe('Sólo fotos · 2');
    expect(placeMeta(living)).toBe('2 recorridos');
    expect(placeMeta(bath)).toBe('Sin material todavía');
    expect(latestCell(front)?.month).toBe('2026-10');
    expect(latestCell(bath)).toBeNull();
  });

  it('lo que no tiene sector va a General, al final; un lugar que ya no es sector igual aparece', () => {
    const grid = placeGrid(sectors, [capture('c1', 'Cochera', '2026-09-01')], [],
      [aMedia({ id: 'a', unitId: 'u1', sector: null, progressRootId: 'e1', progressEntryId: 'e1' })],
      [anEntry({ rootId: 'e1', entryDate: '2026-09-10' })], 'u1');

    expect(grid.rows.map(row => row.name)).toEqual(['Frente', 'Living', 'Baño', 'Cochera', 'General']);
  });

  it('una foto de un avance que ya no está vigente no cuenta', () => {
    const grid = placeGrid(sectors, [], [], [aMedia({ unitId: 'u1', sector: 'Living', progressRootId: 'viejo', progressEntryId: 'viejo' })], [], 'u1');
    expect(grid.months).toEqual([]);
    expect(grid.rows.every(row => row.photos === 0)).toBe(true);
  });

  it('nombra los meses corto en la cabecera y largo en el detalle', () => {
    expect(monthHead('2026-10')).toBe('Oct 26');
    expect(monthLong('2026-06')).toBe('junio 2026');
  });
});
