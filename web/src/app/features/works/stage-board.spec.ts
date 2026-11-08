import { aStage, anEntry } from '../../testing/fixtures';
import { boardCounts, isOverdue, lastEntryByStage, stageBoard } from './stage-board';

const entry = anEntry;

describe('Tablero de etapas', () => {
  it('una etapa abierta que pasó su fin previsto está vencida; una terminada o sin fecha, no', () => {
    expect(isOverdue(aStage({ status: 'IN_PROGRESS', plannedEnd: '2026-10-01' }), '2026-10-23')).toBe(true);
    expect(isOverdue(aStage({ status: 'PENDING', plannedEnd: '2026-10-01' }), '2026-10-23')).toBe(true);
    expect(isOverdue(aStage({ status: 'COMPLETED', plannedEnd: '2026-10-01' }), '2026-10-23')).toBe(false);
    expect(isOverdue(aStage({ status: 'IN_PROGRESS', plannedEnd: null }), '2026-10-23')).toBe(false);
    expect(isOverdue(aStage({ status: 'IN_PROGRESS', plannedEnd: '2026-10-23' }), '2026-10-23')).toBe(false);
  });

  it('el último avance de cada etapa es el vigente más nuevo; en el mismo día, el último publicado', () => {
    const last = lastEntryByStage([
      entry({ id: 'a', entryDate: '2026-10-01' }),
      entry({ id: 'b', entryDate: '2026-10-14', createdAt: '2026-10-14T10:00:00Z' }),
      entry({ id: 'c', entryDate: '2026-10-14', createdAt: '2026-10-14T18:00:00Z' }),
      entry({ id: 'd', entryDate: '2026-10-20', current: false }),
      entry({ id: 'e', stageId: null, entryDate: '2026-10-22' }),
    ]);
    expect(last.get('s1')?.id).toBe('c');
    expect(last.size).toBe(1);
  });

  it('agrupa en curso (con las demoradas), lo que viene y lo terminado, en el orden de la obra', () => {
    const board = stageBoard([
      aStage({ id: 's3', orderIndex: 3, status: 'DELAYED' }),
      aStage({ id: 's1', orderIndex: 1, status: 'COMPLETED' }),
      aStage({ id: 's2', orderIndex: 2, status: 'IN_PROGRESS', plannedEnd: '2026-09-30' }),
      aStage({ id: 's4', orderIndex: 4, status: 'PENDING' }),
    ], [entry({ stageId: 's2' })], '2026-10-23');

    expect(board.active.map(card => card.stage.id)).toEqual(['s2', 's3']);
    expect(board.active[0].overdue).toBe(true);
    expect(board.active[0].last?.id).toBe('e1');
    expect(board.active[1].last).toBeNull();
    expect(board.upcoming.map(card => card.stage.id)).toEqual(['s4']);
    expect(board.done.map(card => card.stage.id)).toEqual(['s1']);
    // Con demora son las dos: s3 declarada Demorada y s2 por haber pasado su fin previsto.
    expect(boardCounts(board)).toBe('1 terminada · 2 en curso (2 con demora) · 1 por empezar');
  });

  it('los conteos dicen sólo lo que hay', () => {
    expect(boardCounts(stageBoard([aStage({ status: 'PENDING' })], [], '2026-10-23'))).toBe('1 por empezar');
    expect(boardCounts(stageBoard([aStage({ status: 'IN_PROGRESS' }), aStage({ id: 's2', status: 'IN_PROGRESS' })], [], '2026-10-23')))
      .toBe('2 en curso');
  });
});
