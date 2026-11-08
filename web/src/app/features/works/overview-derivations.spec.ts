import { HouseCard } from '../../core/api/api.models';
import { aPlaceMonth, aPublicMedia, aShareLink, aStage, anActivityItem } from '../../testing/fixtures';
import {
  activityLine, buyerLines, changeChip, compactMoney, deliveryLine, houseActivityLine, cardStageLine, initialsOf,
  placeTiles, proposalLine, relativeWhen, reviewItems, shortYearMonth, stageMeta, stageSummary,
} from './overview-derivations';

const card = (overrides: Partial<HouseCard> = {}): HouseCard => ({
  unitId: 'u1', currentStageName: null, otherRunningStages: 0, plannedStart: null, lastActivity: null,
  unseen: null, hasSupervisor: null, alertLight: null, alertStageName: null, ...overrides,
});

describe('Novedades en una línea', () => {
  it('un avance dice en qué etapa; las fotos, cuántas y de qué lugares; un recorrido, su estado', () => {
    expect(activityLine(anActivityItem())).toEqual({ who: 'Carlos Medina', what: 'publicó un avance en Instalación eléctrica' });
    const photos = anActivityItem({
      kind: 'PHOTOS', entry: null,
      session: { day: '2026-10-20', photos: 9, videos: 0, sectors: ['Frente', 'Living', 'Cocina', 'Baño'], thumbnails: [] },
    });
    expect(activityLine(photos).what).toBe('subió 9 fotos de Frente, Living y 2 lugares más');
    const capture = anActivityItem({
      kind: 'CAPTURE', entry: null,
      capture: { captureId: 'c1', captureDate: '2026-10-20', zone: 'Dormitorio principal', state: 'READY', failureReason: null },
    });
    expect(activityLine(capture)).toEqual({ who: 'Recorrido 3D', what: 'de Dormitorio principal listo' });
    expect(initialsOf(capture)).toBe('3D');
    expect(initialsOf(anActivityItem())).toBe('CM');
  });

  it('un aviso de materiales dice quién avisó y qué, siguiendo la frase; con varios, cuántos más', () => {
    const supply = (alerts: string[]) => anActivityItem({ kind: 'SUPPLY', entry: null, supply: { extractionId: 'x1', alerts } });
    expect(activityLine(supply(['Falta el tablero eléctrico'])))
      .toEqual({ who: 'Carlos Medina', what: 'avisó: falta el tablero eléctrico' });
    expect(activityLine(supply(['Falta arena', 'PVC de 110 demorado', 'Faltan clavos'])).what)
      .toBe('avisó: falta arena y 2 avisos más');
    expect(activityLine(supply(['PVC de 110 demorado'])).what).toBe('avisó: PVC de 110 demorado');
  });

  it('el chip del cambio de etapa va sin el nombre, que ya está en la línea', () => {
    const entry = anActivityItem().entry!;
    expect(changeChip({ ...entry, stageChange: { percentBefore: 35, percentAfter: 50, statusBefore: null, statusAfter: null } }))
      .toBe('35% → 50%');
    expect(changeChip({ ...entry, stageChange: { percentBefore: 60, percentAfter: 60, statusBefore: 'IN_PROGRESS', statusAfter: 'DELAYED' } }))
      .toBe('En curso → Demorada');
    expect(changeChip(entry)).toBeNull();
  });

  it('cuándo: hoy, ayer, hace unos días y, después, la fecha', () => {
    expect(relativeWhen('2026-10-20T15:00:00Z', '2026-10-20')).toBe('hoy');
    expect(relativeWhen('2026-10-19T15:00:00Z', '2026-10-20')).toBe('ayer');
    expect(relativeWhen('2026-10-17T15:00:00Z', '2026-10-20')).toBe('hace 3 días');
    expect(relativeWhen('2026-10-01T15:00:00Z', '2026-10-20')).toBe('1 de octubre');
  });
});

describe('Indicadores', () => {
  it('los montos van compactos y en castellano', () => {
    expect(compactMoney(41_200_000)).toBe('US$ 41,2 M');
    expect(compactMoney(850_000)).toBe('US$ 850 mil');
    expect(compactMoney(900)).toBe('US$ 900');
  });

  it('la entrega: el mes corto y cuánto falta', () => {
    expect(shortYearMonth('2027-07')).toBe('Jul 2027');
    expect(deliveryLine('2027-07', '2026-10-20')).toBe('Faltan 9 meses');
    expect(deliveryLine('2026-10', '2026-10-20')).toBe('Es este mes');
    expect(deliveryLine('2026-08', '2026-10-20')).toBe('La fecha ya pasó');
    expect(deliveryLine(null)).toBe('Sin fecha definida');
  });
});

describe('Tarjeta de una casa', () => {
  const unit = { progressPercent: 68, stageCount: 12, currentStageName: 'Revoques' };

  it('la etapa principal con "+2" si se trabajan más; terminada o sin empezar con su inicio', () => {
    expect(cardStageLine(unit, card({ currentStageName: 'Instalación sanitaria', otherRunningStages: 2 }))).toBe('Instalación sanitaria +2');
    expect(cardStageLine({ ...unit, progressPercent: 100 }, card())).toBe('Terminada');
    expect(cardStageLine({ ...unit, progressPercent: 0 }, card({ plannedStart: '2026-11-03' }))).toBe('Sin empezar · inicia en noviembre');
    expect(cardStageLine({ ...unit, stageCount: 0 }, null)).toBe('Sin etapas cargadas');
  });

  it('el pie: lo último que pasó, y si no hay nada, si le falta encargado', () => {
    expect(houseActivityLine(card({ lastActivity: anActivityItem({ at: '2026-10-19T15:00:00Z' }) }), '2026-10-20'))
      .toBe('Carlos publicó un avance en Instalación eléctrica · ayer');
    expect(houseActivityLine(card({ hasSupervisor: false }))).toBe('Sin encargado asignado');
    expect(houseActivityLine(card())).toBe('Todavía sin novedades');
  });
});

describe('Para revisar', () => {
  it('audios primero, con lo que proponen; después las alertas, con gasto y avance', () => {
    const items = reviewItems('d1', [{
      extractionId: 'x1', unitId: 'u1', unitCode: 'Casa 1', authorId: 'p1', authorName: 'Carlos Medina',
      createdAt: '2026-10-20T12:00:00Z', progressNotes: 2, stageChanges: 1,
    }], [{
      stageId: 's1', name: 'Cubierta y techo', unitId: 'u2', unitCode: 'Casa 2', trafficLight: 'RED', budgetUsedPercent: 62, progressPercent: 30,
    }, {
      stageId: 's2', name: 'Cordón cuneta', unitId: null, unitCode: null, trafficLight: 'YELLOW', budgetUsedPercent: null, progressPercent: null,
    }]);

    expect(items.map(item => [item.title, item.detail, item.link.join('/'), item.action])).toEqual([
      ['Audio de Carlos · Casa 1', '2 novedades, 1 cambio de etapa', '/obras/d1/casas/u1/historia/audios/x1', 'Revisar'],
      ['Casa 2 · Cubierta y techo', 'Gastó 62% del presupuesto con 30% de avance', '/obras/d1/casas/u2/economico', 'Ver'],
      ['Obras comunes · Cordón cuneta', 'El gasto va por delante del avance', '/obras/d1/economico', 'Ver'],
    ]);
    expect(proposalLine(0, 0)).toBe('Sin cambios propuestos');
  });
});

describe('Resumen de la casa', () => {
  it('cuenta etapas terminadas, en curso, con demora y la próxima', () => {
    expect(stageSummary([
      aStage({ id: 'a', orderIndex: 1, status: 'COMPLETED' }),
      aStage({ id: 'b', orderIndex: 2, status: 'DELAYED' }),
      aStage({ id: 'c', orderIndex: 3, status: 'IN_PROGRESS' }),
      aStage({ id: 'd', orderIndex: 5, status: 'PENDING', name: 'Pintura' }),
      aStage({ id: 'e', orderIndex: 4, status: 'PENDING', name: 'Aberturas' }),
    ], '2026-10-23')).toEqual({ done: 1, total: 5, running: 2, delayed: 1, next: 'Aberturas' });
    // La demora es la de toda la app: declarada o por haber pasado el fin previsto sin terminar.
    expect(stageSummary([
      aStage({ id: 'a', orderIndex: 1, status: 'IN_PROGRESS', plannedEnd: '2026-09-20' }),
      aStage({ id: 'b', orderIndex: 2, status: 'COMPLETED', plannedEnd: '2026-09-20' }),
    ], '2026-10-23').delayed).toBe(1);
    expect(stageMeta({ startedOn: '2026-10-08', plannedEnd: '2026-12-15', status: 'IN_PROGRESS' })).toBe('En curso desde el 8 oct · fin previsto dic');
  });

  it('cada lugar con su miniatura más nueva, si tuvo 3D y qué tiene en su último mes', () => {
    const tiles = placeTiles([
      { name: 'Living', months: [
        aPlaceMonth('2026-09', { tour: true, photos: [aPublicMedia('https://s/sep.jpg')] }),
        aPlaceMonth('2026-10', { tour: true, photos: [aPublicMedia('https://s/oct.jpg', { entryDate: '2026-10-20' })] }),
      ] },
      { name: 'Segundo dormitorio', months: [aPlaceMonth('2026-10', { tour: true })] },
    ]);
    expect(tiles.map(tile => [tile.name, tile.thumb?.url ?? null, tile.has3d, tile.meta])).toEqual([
      ['Living', 'https://s/oct.jpg', true, '3D y fotos · 20 oct'],
      ['Segundo dormitorio', null, true, '3D · oct 2026'],
    ]);
  });

  it('el comprador: su nombre, los enlaces activos y el último acceso', () => {
    const link = (active: boolean, lastAccessedAt: string | null) => aShareLink({ id: 'l', label: null, active, lastAccessedAt, accessCount: 1 });
    expect(buyerLines([{ fullName: 'Familia Benítez', email: null }], [link(true, '2026-10-18T10:00:00Z'), link(false, '2026-10-19T10:00:00Z')], '2026-10-20'))
      .toEqual({ name: 'Familia Benítez', detail: '1 enlace activo · último acceso hace 2 días' });
    expect(buyerLines([], [])).toEqual({ name: 'Sin comprador', detail: 'Sin enlaces activos' });
  });
});
