import { PublicStage, PublicUnitView } from '../../core/api/api.models';
import { aPlaceMonth, aPublicEntry, aPublicMedia, aPublicStage, publicDetails, publicDevelopment } from '../../testing/fixtures';
import {
  changeLine, entriesThisMonth, greetingName, heroEntry, heroTitle, longYearMonth, monthStory, monthsLeft, placeCards,
  schedule, sinceLabel, stageCells, upcoming,
} from './buyer-derivations';

const TODAY = '2026-10-20';

function stage(name: string, orderIndex: number, status: PublicStage['status'], overrides: Partial<PublicStage> = {}): PublicStage {
  return { ...aPublicStage(name, orderIndex, status, status === 'COMPLETED' ? 100 : status === 'PENDING' ? 0 : 50), ...overrides };
}

function view(overrides: Partial<PublicUnitView> = {}): PublicUnitView {
  return {
    unitCode: 'Casa 1', unitDescription: null, developmentName: 'Altos del Valle', developmentAddress: null,
    developmentKind: 'MULTI_UNIT', details: publicDetails({ estimatedDelivery: '2027-11' }), overallProgressPercent: 68,
    currentStage: null, stages: [], timeline: [], captures: [], gallery: [], plans: [], comparisons: [], showcase: [],
    development: publicDevelopment(), sectors: [], places: [],
    front: { sector: null, latest: null, first: null, renderUrl: null, project: null },
    ...overrides,
  } as PublicUnitView;
}

describe('Portada de Mi casa', () => {
  it('el título dice lo que es cierto hoy', () => {
    expect(heroTitle(view({ stages: [stage('A', 1, 'IN_PROGRESS')] }))).toBe('Tu casa avanza.');
    expect(heroTitle(view({ overallProgressPercent: 0, stages: [stage('A', 1, 'PENDING')] }))).toBe('Tu casa está por empezar.');
    expect(heroTitle(view({ overallProgressPercent: 100, stages: [stage('A', 1, 'COMPLETED')] }))).toBe('Tu casa está lista.');
  });

  it('la frase es el último avance de la casa; uno de obras comunes sólo si la casa no tiene', () => {
    const own = aPublicEntry({ description: 'Cableado de planta alta', date: '2026-10-18' });
    const common = aPublicEntry({ description: 'Asfalto de la calle', date: '2026-10-19', common: true });
    expect(heroEntry(view({ timeline: [common, own] }))?.description).toBe('Cableado de planta alta');
    expect(heroEntry(view({ timeline: [common] }))?.description).toBe('Asfalto de la calle');
    expect(heroEntry(view())).toBeNull();
  });

  it('la carga inicial no es la frase de la portada, ni cuenta en el mes, ni es "Este mes en tu casa"', () => {
    const initial = aPublicEntry({ description: 'Revoque grueso de planta baja', date: '2026-10-19', source: 'INITIAL_LOAD' });
    const own = aPublicEntry({ description: 'Cañerías del primer piso', date: '2026-10-14' });
    expect(heroEntry(view({ timeline: [initial, own] }))?.description).toBe('Cañerías del primer piso');
    expect(heroEntry(view({ timeline: [initial] }))).toBeNull();
    expect(entriesThisMonth(view({ timeline: [initial, own] }), TODAY)).toBe('1 avance este mes');
    expect(monthStory(view({ timeline: [initial] }))).toBeNull();
    expect(monthStory(view({ timeline: [initial, own] }))?.facts.map(fact => fact.text)).toEqual(['Cañerías del primer piso']);
  });

  it('cuándo fue la última novedad, en días de calendario', () => {
    expect(sinceLabel('2026-10-20', TODAY)).toBe('hoy');
    expect(sinceLabel('2026-10-18', TODAY)).toBe('hace 2 días');
    expect(sinceLabel('2026-10-08', TODAY)).toBe('el 8 de octubre');
  });

  it('saluda por el nombre de pila', () => {
    expect(greetingName('Lucía Paz')).toBe('Lucía');
    expect(greetingName(null)).toBeNull();
  });
});

describe('En tiempo o con demora', () => {
  const planned = [
    stage('Estructura', 1, 'COMPLETED', { plannedEnd: '2026-05-30' }),
    stage('Revoques', 2, 'IN_PROGRESS', { plannedEnd: '2026-12-15' }),
    stage('Aberturas', 3, 'PENDING', { plannedEnd: '2027-09-30' }),
  ];

  it('"En tiempo" sólo con todas las fechas previstas, ninguna vencida y la última antes de la entrega', () => {
    expect(schedule(view({ stages: planned }), TODAY)).toEqual({ kind: 'ON_TIME', reason: null });
  });

  it('si falta una fecha prevista, o la última termina después de la entrega, no se afirma nada', () => {
    expect(schedule(view({ stages: [...planned.slice(0, 2), stage('Aberturas', 3, 'PENDING')] }), TODAY)).toBeNull();
    expect(schedule(view({ stages: planned, details: publicDetails({ estimatedDelivery: '2027-06' }) }), TODAY)).toBeNull();
    expect(schedule(view({ stages: planned, details: publicDetails({ estimatedDelivery: null }) }), TODAY)).toBeNull();
  });

  it('una etapa demorada da el motivo del avance que la marcó así', () => {
    const delayed = [planned[0], stage('Revoques', 2, 'DELAYED', { plannedEnd: '2026-12-15' }), planned[2]];
    const reason = aPublicEntry({
      stageName: 'Revoques', description: 'Se frenó por las lluvias; se retoma la semana que viene.',
      stageChange: { percentBefore: 50, percentAfter: 50, statusBefore: 'IN_PROGRESS', statusAfter: 'DELAYED' },
    });
    expect(schedule(view({ stages: delayed, timeline: [reason] }), TODAY))
      .toEqual({ kind: 'DELAYED', reason: 'Se frenó por las lluvias; se retoma la semana que viene.' });
    expect(schedule(view({ stages: delayed }), TODAY)).toEqual({ kind: 'DELAYED', reason: 'Revoques lleva más de lo previsto.' });
  });

  it('una etapa que pasó su fin previsto sin terminar, o una entrega vencida, es una demora', () => {
    const late = [stage('Revoques', 2, 'IN_PROGRESS', { plannedEnd: '2026-09-30' })];
    expect(schedule(view({ stages: late }), TODAY)).toEqual({ kind: 'DELAYED', reason: 'Revoques lleva más de lo previsto.' });
    expect(schedule(view({ stages: [stage('A', 1, 'IN_PROGRESS')], details: publicDetails({ estimatedDelivery: '2026-08' }) }), TODAY))
      .toEqual({ kind: 'DELAYED', reason: 'Ya pasó el mes de entrega estimado.' });
  });

  it('una casa terminada no lleva insignia', () => {
    expect(schedule(view({ overallProgressPercent: 100, stages: [stage('A', 1, 'COMPLETED')] }), TODAY)).toBeNull();
  });

  it('la entrega en palabras', () => {
    expect(longYearMonth('2027-11')).toBe('Noviembre 2027');
    expect(monthsLeft('2027-11', TODAY)).toBe('Faltan unos 13 meses.');
    expect(monthsLeft('2026-11', TODAY)).toBe('Falta un mes.');
    expect(monthsLeft('2026-08', TODAY)).toBeNull();
  });
});

describe('Este mes en tu casa', () => {
  it('cuántos avances de la casa hubo este mes; sin ninguno, nada', () => {
    const timeline = [aPublicEntry({ date: '2026-10-18' }), aPublicEntry({ date: '2026-10-02' }), aPublicEntry({ date: '2026-10-05', common: true })];
    expect(entriesThisMonth(view({ timeline }), TODAY)).toBe('2 avances este mes');
    expect(entriesThisMonth(view({ timeline: [aPublicEntry({ date: '2026-09-18' })] }), TODAY)).toBeNull();
  });

  it('los hechos del último mes con avances de la casa, con el cambio de etapa como título, y sus fotos', () => {
    const story = monthStory(view({
      timeline: [
        aPublicEntry({
          date: '2026-10-20', stageName: 'Instalación eléctrica', description: 'Cableado de toda la planta alta.',
          stageChange: { percentBefore: 35, percentAfter: 50, statusBefore: 'IN_PROGRESS', statusAfter: 'IN_PROGRESS' },
          photos: [aPublicMedia('https://s/avance.jpg', { entryDate: '2026-10-20' })],
        }),
        aPublicEntry({ date: '2026-10-14', stageName: 'Gas y agua', description: 'Falta la prueba de hermeticidad.' }),
        aPublicEntry({ date: '2026-09-02', description: 'Del mes anterior' }),
      ],
      places: [{ name: 'Living', months: [aPlaceMonth('2026-10', { photos: [aPublicMedia('https://s/living.jpg', { takenOn: '2026-10-19' })] })] }],
    }))!;

    expect(story.month).toBe('2026-10');
    expect(story.facts.map(fact => fact.title)).toEqual(['Instalación eléctrica: 35% → 50%', 'Gas y agua']);
    expect(story.photos.map(photo => [photo.media.url, photo.sector])).toEqual([
      ['https://s/avance.jpg', null], ['https://s/living.jpg', 'Living'],
    ]);
    expect(monthStory(view({ timeline: [aPublicEntry({ common: true })] }))).toBeNull();
  });

  it('un cambio sólo de estado se dice con el estado', () => {
    expect(changeLine('Revoques', { percentBefore: 60, percentAfter: 60, statusBefore: 'IN_PROGRESS', statusAfter: 'COMPLETED' }))
      .toBe('Revoques: terminada');
  });
});

describe('Lo que viene, lugares y camino', () => {
  const stages = [
    stage('Revoques', 1, 'IN_PROGRESS'),
    stage('Aberturas', 2, 'PENDING', { plannedStart: '2026-12-01', description: 'Se colocan las ventanas.' }),
    stage('Pisos', 3, 'PENDING'),
    stage('Pintura', 4, 'PENDING'),
  ];

  it('la próxima etapa con su mes previsto, lo que sigue y lo último', () => {
    const next = upcoming(view({ stages }), TODAY)!;
    expect([next.stage.name, next.when, next.after, next.last]).toEqual(['Aberturas', 'diciembre', 'Pisos', 'Pintura']);
    expect(upcoming(view({ overallProgressPercent: 100, stages: [stage('A', 1, 'COMPLETED')] }))).toBeNull();
    // Si el mes previsto ya pasó, no se lo nombra.
    const late = stages.map(item => item.name === 'Aberturas' ? { ...item, plannedStart: '2026-09-01' } : item);
    expect(upcoming(view({ stages: late }), TODAY)?.when).toBeNull();
  });

  it('cada lugar con su foto más nueva, el 3D y de qué meses tiene material', () => {
    const cards = placeCards([{ name: 'Living', months: [
      aPlaceMonth('2026-07', { photos: [aPublicMedia('https://s/jul.jpg')] }),
      aPlaceMonth('2026-10', { tour: true }),
    ] }]);
    expect(cards.map(card => [card.name, card.photo?.url, card.has3d, card.months])).toEqual([['Living', 'https://s/jul.jpg', true, 'Jul – Oct']]);
  });

  it('el camino: terminada, en curso (la demorada también, sin alarma) y por venir', () => {
    const cells = stageCells([stage('B', 2, 'DELAYED'), stage('A', 1, 'COMPLETED'), stage('C', 3, 'PENDING')]);
    expect(cells.map(cell => [cell.number, cell.label])).toEqual([['01', 'Terminada'], ['02', 'En curso'], ['03', 'Por venir']]);
  });
});
