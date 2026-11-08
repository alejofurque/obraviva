import { ProgressEntry, Stage, StageStatus } from '../../core/api/api.models';
import { STAGE_STATUS_LABELS, hasDelay, isPastPlannedEnd } from '../../shared/ui/stage-status';

/** Cómo se nombra el estado de una etapa en el tablero: las etiquetas de toda la parte interna. */
export const STAGE_STATE_LABELS: Record<StageStatus, string> = STAGE_STATUS_LABELS;

/** Una etapa abierta que pasó su fin previsto. Se marca aunque nadie la haya declarado demorada. */
export function isOverdue(stage: Pick<Stage, 'status' | 'plannedEnd'>, today: string): boolean {
  return isPastPlannedEnd(stage, today);
}

/** El último avance vigente de cada etapa: el de fecha más nueva y, en el mismo día, el último publicado. */
export function lastEntryByStage(entries: ProgressEntry[]): Map<string, ProgressEntry> {
  const last = new Map<string, ProgressEntry>();
  for (const entry of entries) {
    if (!entry.current || !entry.stageId) {
      continue;
    }
    const known = last.get(entry.stageId);
    if (!known || entry.entryDate > known.entryDate
        || (entry.entryDate === known.entryDate && entry.createdAt > known.createdAt)) {
      last.set(entry.stageId, entry);
    }
  }
  return last;
}

export interface StageCard {
  stage: Stage;
  overdue: boolean;
  /** Tiene demora: declarada o por haber pasado su fin previsto (`hasDelay`). */
  late: boolean;
  /** Nulo si la etapa todavía no tiene avances: la línea no aparece. */
  last: ProgressEntry | null;
}

export interface StageBoard {
  /** En ejecución y demoradas, en el orden de la obra. */
  active: StageCard[];
  upcoming: StageCard[];
  done: StageCard[];
  delayed: number;
}

/** "Por estado": lo que se hace ahora con su último avance, lo que viene y lo terminado. */
export function stageBoard(stages: Stage[], entries: ProgressEntry[], today: string): StageBoard {
  const last = lastEntryByStage(entries);
  const cards = [...stages]
    .sort((a, b) => a.orderIndex - b.orderIndex)
    .map(stage => ({ stage, overdue: isOverdue(stage, today), late: hasDelay(stage, today), last: last.get(stage.id) ?? null }));
  return {
    active: cards.filter(card => card.stage.status === 'IN_PROGRESS' || card.stage.status === 'DELAYED'),
    upcoming: cards.filter(card => card.stage.status === 'PENDING'),
    done: cards.filter(card => card.stage.status === 'COMPLETED'),
    delayed: cards.filter(card => card.late).length,
  };
}

/** "7 terminadas · 3 en curso (1 con demora) · 2 por empezar": sólo lo que hay. */
export function boardCounts(board: StageBoard): string {
  const parts: string[] = [];
  if (board.done.length > 0) {
    parts.push(`${board.done.length} ${board.done.length === 1 ? 'terminada' : 'terminadas'}`);
  }
  if (board.active.length > 0) {
    parts.push(`${board.active.length} en curso${board.delayed > 0 ? ` (${board.delayed} con demora)` : ''}`);
  }
  if (board.upcoming.length > 0) {
    parts.push(`${board.upcoming.length} por empezar`);
  }
  return parts.join(' · ');
}
