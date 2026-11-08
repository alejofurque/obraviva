import { ActivityItem, ActivityStageChange } from '../../core/api/api.models';
import { anActivityItem } from '../../testing/fixtures';
import { activityDays, activityLinks, dayLabel, sessionLine, stageChangeLines } from './activity-derivations';

describe('Novedades', () => {
  it('nombra el día: Hoy, Ayer, la fecha, y el año si no es el de hoy', () => {
    expect(dayLabel('2026-10-20', '2026-10-20')).toBe('Hoy');
    expect(dayLabel('2026-10-19', '2026-10-20')).toBe('Ayer');
    expect(dayLabel('2026-10-15', '2026-10-20')).toBe('15 de octubre');
    expect(dayLabel('2025-12-31', '2026-01-01')).toBe('Ayer');
    expect(dayLabel('2025-12-30', '2026-01-01')).toBe('30 de diciembre de 2025');
  });

  it('agrupa por día en el orden del feed, en la hora del navegador', () => {
    const days = activityDays([
      anActivityItem({ id: 'a', at: '2026-10-20T15:00:00' }),
      anActivityItem({ id: 'b', at: '2026-10-20T09:00:00' }),
      anActivityItem({ id: 'c', at: '2026-10-19T20:00:00' }),
      anActivityItem({ id: 'd', at: '2026-10-15T10:00:00' }),
    ], '2026-10-20');

    expect(days.map(day => [day.label, day.items.map(item => item.id)])).toEqual([
      ['Hoy', ['a', 'b']], ['Ayer', ['c']], ['15 de octubre', ['d']],
    ]);
  });

  it('el cambio de etapa: el porcentaje con su etapa y, si cambió, el estado', () => {
    const change = (stageChange: ActivityStageChange | null) =>
      stageChangeLines({ ...anActivityItem().entry!, stageChange });

    expect(change({ percentBefore: 35, percentAfter: 50, statusBefore: 'IN_PROGRESS', statusAfter: 'IN_PROGRESS' }))
      .toEqual(['Instalación eléctrica 35% → 50%']);
    expect(change({ percentBefore: 90, percentAfter: 100, statusBefore: 'IN_PROGRESS', statusAfter: 'COMPLETED' }))
      .toEqual(['Instalación eléctrica 90% → 100%', 'En curso → Terminada']);
    expect(change({ percentBefore: 40, percentAfter: 40, statusBefore: 'IN_PROGRESS', statusAfter: 'DELAYED' }))
      .toEqual(['En curso → Demorada']);
    // Un avance viejo, o uno que no tocó la etapa, no dice nada.
    expect(change(null)).toEqual([]);
  });

  it('una sesión de fotos en una línea: quién, cuántas y dónde', () => {
    const item = anActivityItem({ kind: 'PHOTOS', entry: null });
    expect(sessionLine(item, { day: '2026-10-20', photos: 4, videos: 0, sectors: ['Frente', 'Living'], thumbnails: [] }))
      .toBe('Carlos subió 4 fotos · Frente, Living');
    expect(sessionLine(item, { day: '2026-10-20', photos: 1, videos: 1, sectors: ['Frente'], thumbnails: [] }))
      .toBe('Carlos subió 1 foto y 1 video · Frente');
  });

  it('cada ítem lleva a la casa y, si corresponde, a corregir con lo que ya existe', () => {
    expect(activityLinks(anActivityItem(), 'd1')).toEqual({
      view: ['/obras', 'd1', 'casas', 'u1', 'historia'],
      correct: { link: ['/obras', 'd1', 'casas', 'u1', 'historia'], query: { corregir: 'e2' } },
    });
    expect(activityLinks(anActivityItem({ kind: 'PHOTOS', entry: null }), 'd1')).toEqual({
      view: ['/obras', 'd1', 'casas', 'u1'],
      correct: { link: ['/obras', 'd1', 'casas', 'u1', 'recorridos'], query: null },
    });
    expect(activityLinks(anActivityItem({ kind: 'CAPTURE', entry: null }), 'd1'))
      .toEqual({ view: ['/obras', 'd1', 'casas', 'u1', 'recorridos'], correct: null });
    expect(activityLinks(anActivityItem({ unitId: null, unitCode: null }), 'd1').view).toEqual(['/obras', 'd1', 'comunes', 'historia']);
    // Un aviso de materiales no se corrige: no es algo publicado.
    expect(activityLinks(anActivityItem({ kind: 'SUPPLY', entry: null, supply: { extractionId: 'x1', alerts: ['Falta arena'] } }), 'd1'))
      .toEqual({ view: ['/obras', 'd1', 'casas', 'u1'], correct: null });
  });
});
