import { aStage } from '../../testing/fixtures';
import { formatShortDate, shortMonth } from '../time/short-date';
import { BUYER_STAGE_STATUS_LABELS, STAGE_STATUS_LABELS, STAGE_STATUS_OPTIONS, hasDelay } from './stage-status';
import { UPDATE_STATUSES } from './stage-update.component';

describe('Un solo vocabulario', () => {
  it('los estados internos son Por empezar, En curso, Terminada y Demorada; el comprador ve "Por venir"', () => {
    expect(STAGE_STATUS_LABELS).toEqual({ PENDING: 'Por empezar', IN_PROGRESS: 'En curso', COMPLETED: 'Terminada', DELAYED: 'Demorada' });
    expect(STAGE_STATUS_OPTIONS.map(option => option.label)).toEqual(['Por empezar', 'En curso', 'Demorada', 'Terminada']);
    expect(UPDATE_STATUSES.map(option => option.label)).toEqual(['En curso', 'Demorada', 'Terminada']);
    expect(BUYER_STAGE_STATUS_LABELS.PENDING).toBe('Por venir');
  });

  it('una etapa tiene demora si está Demorada o si pasó su fin previsto sin terminar', () => {
    const today = '2026-10-23';
    expect(hasDelay(aStage({ status: 'DELAYED', plannedEnd: null }), today)).toBe(true);
    expect(hasDelay(aStage({ status: 'IN_PROGRESS', plannedEnd: '2026-09-20' }), today)).toBe(true);
    expect(hasDelay(aStage({ status: 'PENDING', plannedEnd: '2026-09-20' }), today)).toBe(true);
    expect(hasDelay(aStage({ status: 'COMPLETED', plannedEnd: '2026-09-20' }), today)).toBe(false);
    expect(hasDelay(aStage({ status: 'IN_PROGRESS', plannedEnd: '2026-10-23' }), today)).toBe(false);
  });

  it('los meses se abrevian con "sep", sin la t, en todos los formatos cortos', () => {
    expect(formatShortDate('2026-09-20', 'd MMM')).toBe('20 sep');
    expect(formatShortDate('2026-09-01', 'd MMM y')).toBe('1 sep 2026');
    expect(formatShortDate('2026-09-01', 'd MMM yy')).toBe('1 sep 26');
    expect(formatShortDate('2026-09-01', 'MMM y')).toBe('sep 2026');
    expect(formatShortDate(new Date(2026, 9, 14, 12, 55), 'd MMM y, HH:mm')).toBe('14 oct 2026, 12:55');
    expect(shortMonth(8, true)).toBe('Sep');
    expect(formatShortDate(null)).toBeNull();
  });
});
