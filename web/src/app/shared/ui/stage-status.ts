import { Stage, StageStatus } from '../../core/api/api.models';

/**
 * Cómo se nombra el estado de una etapa en todo lo interno (dueño y encargado): la etapa
 * está por empezar, en curso, terminada o demorada. Las etiquetas salen sólo de acá; los
 * valores de la API no cambian.
 */
export const STAGE_STATUS_LABELS: Record<StageStatus, string> = {
  PENDING: 'Por empezar',
  IN_PROGRESS: 'En curso',
  COMPLETED: 'Terminada',
  DELAYED: 'Demorada',
};

/** Para el comprador, lo que todavía no empezó "viene": la etapa está por venir. */
export const BUYER_STAGE_STATUS_LABELS: Record<StageStatus, string> = {
  ...STAGE_STATUS_LABELS,
  PENDING: 'Por venir',
};

/** Los cuatro estados para elegir, en el orden de la obra. */
export const STAGE_STATUS_OPTIONS: { value: StageStatus; label: string }[] =
  (['PENDING', 'IN_PROGRESS', 'DELAYED', 'COMPLETED'] as const).map(value => ({ value, label: STAGE_STATUS_LABELS[value] }));

/** Una etapa abierta que pasó su fin previsto. Se marca aunque nadie la haya declarado demorada. */
export function isPastPlannedEnd(stage: Pick<Stage, 'status' | 'plannedEnd'>, today: string): boolean {
  return stage.status !== 'COMPLETED' && stage.plannedEnd !== null && stage.plannedEnd < today;
}

/**
 * Si una etapa tiene demora: está declarada Demorada o pasó su fin previsto sin terminar. Es
 * el único cálculo: lo usan el contador del Resumen, Etapas y la etiqueta. Siempre en ámbar.
 */
export function hasDelay(stage: Pick<Stage, 'status' | 'plannedEnd'>, today: string): boolean {
  return stage.status === 'DELAYED' || isPastPlannedEnd(stage, today);
}
