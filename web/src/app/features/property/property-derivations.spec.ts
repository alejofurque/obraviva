import { PublicCapture, PublicMedia, PublicProgressEntry, PublicUnitView } from '../../core/api/api.models';
import {
  busiestZone,
  captureAtMonth,
  captureIndexAt,
  captureMoments,
  captureMonths,
  constructionState,
  heroImage,
  historyMonths,
  houseFacts,
  lastUpdate,
  longMonth,
  monthHeading,
  monthStop,
  nextStage,
  propertyContext,
  propertyTitle,
  shortMonth,
  stageJourney,
  stagePosition,
  zonesOf,
} from './property-derivations';
import { aPublicStage, publicDetails, publicDevelopment } from '../../testing/fixtures';

function view(overrides: Partial<PublicUnitView> = {}): PublicUnitView {
  return {
    unitCode: 'Casa 12',
    unitDescription: null,
    developmentName: 'Los Aromos',
    developmentAddress: 'Córdoba',
    developmentKind: 'MULTI_UNIT',
    details: publicDetails(),
    overallProgressPercent: 0,
    currentStage: null,
    stages: [],
    timeline: [],
    captures: [],
    gallery: [],
    plans: [],
    comparisons: [],
    showcase: [],
    development: publicDevelopment(),
    sectors: [],
    places: [],
    front: null,
    ...overrides,
  };
}

function photo(url: string, entryDate: string | null, uploadedAt = '2026-01-01T12:00:00Z'): PublicMedia {
  return { url, contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt, entryDate, stageName: null, takenOn: null, thumbUrl: null, screenUrl: null };
}

function capture(date: string, zone: string | null = null): PublicCapture {
  return {
    date, zone, description: null, stageName: null, common: false, splatUrl: `https://s/${date}`,
    transform: { positionX: 0, positionY: 0, positionZ: 0, rotationX: 0, rotationY: 0, rotationZ: 0, scale: 1 },
  };
}

describe('property-derivations', () => {
  describe('nombre de la propiedad', () => {
    it('en una obra única la propiedad es la obra: título con su nombre y la dirección como contexto', () => {
      const single = view({ developmentKind: 'SINGLE', unitCode: 'Principal' });

      expect(propertyTitle(single)).toBe('Los Aromos');
      expect(propertyContext(single)).toBe('Córdoba');
    });

    it('en un edificio la propiedad es la unidad, dentro de la obra', () => {
      expect(propertyTitle(view())).toBe('Casa 12');
      expect(propertyContext(view())).toBe('Los Aromos · Córdoba');
      expect(propertyContext(view({ developmentAddress: null }))).toBe('Los Aromos');
    });
  });

  describe('constructionState', () => {
    it('sin avance ni historia, la obra está por comenzar', () => {
      expect(constructionState(view())).toBe('NOT_STARTED');
    });

    it('con todas las etapas completas, está terminada', () => {
      const stages = [
        aPublicStage('A', 1, 'COMPLETED', 100),
        aPublicStage('B', 2, 'COMPLETED', 100),
      ];
      expect(constructionState(view({ stages, overallProgressPercent: 100 }))).toBe('FINISHED');
    });

    it('con avance parcial, está en construcción', () => {
      expect(constructionState(view({ overallProgressPercent: 40 }))).toBe('IN_CONSTRUCTION');
    });
  });

  it('la última actualización es el avance más nuevo', () => {
    const timeline = [
      { date: '2026-09-10', stageName: null, description: 'b', source: 'MANUAL' as const, corrected: false, photos: [], common: false, stageChange: null },
      { date: '2026-08-27', stageName: null, description: 'a', source: 'MANUAL' as const, corrected: false, photos: [], common: false, stageChange: null },
    ];
    expect(lastUpdate(view({ timeline }))).toBe('2026-09-10');
    expect(lastUpdate(view())).toBeNull();
  });

  it('la próxima etapa es la siguiente no completada en orden de obra', () => {
    const stages = [
      aPublicStage('Estructura', 2, 'IN_PROGRESS', 50),
      aPublicStage('Techos', 3, 'COMPLETED', 100),
      aPublicStage('Terminaciones', 4, 'PENDING', 0),
      aPublicStage('Fundaciones', 1, 'COMPLETED', 100),
    ];
    expect(nextStage(view({ stages, currentStage: stages[0] }))?.name).toBe('Terminaciones');
    expect(nextStage(view({ stages, currentStage: null }))).toBeNull();
  });

  describe('fotos', () => {
    const gallery = [
      photo('hoy', '2026-09-10'),
      photo('suelta', null, '2026-05-01T10:00:00Z'),
      photo('inicio', '2026-02-10'),
    ];

    it('la portada manda; sin portada, la foto más reciente; sin fotos, nada', () => {
      const withCover = view({ gallery, details: { ...view().details, coverUrl: 'portada' } });
      expect(heroImage(withCover)).toBe('portada');
      expect(heroImage(view({ gallery }))).toBe('hoy');
      expect(heroImage(view())).toBeNull();
    });

    it('la portada va en su versión de pantalla cuando está, y si no, el original', () => {
      const details = { ...view().details, coverUrl: 'portada.png', coverThumbUrl: 'portada-mini.jpg', coverScreenUrl: 'portada-pantalla.jpg' };
      expect(heroImage(view({ details }))).toBe('portada-pantalla.jpg');
      expect(heroImage(view({ details: { ...details, coverScreenUrl: null } }))).toBe('portada.png');
    });
  });

  describe('capturas', () => {
    const captures = [capture('2026-02-15'), capture('2026-06-01'), capture('2026-09-10')];

    it('para una fecha elige la última captura de ese día o anterior', () => {
      expect(captureIndexAt(captures, '2026-07-20')).toBe(1);
      expect(captureIndexAt(captures, '2026-09-10')).toBe(2);
    });

    it('si todas son posteriores elige la primera, y sin capturas no hay índice', () => {
      expect(captureIndexAt(captures, '2026-01-01')).toBe(0);
      expect(captureIndexAt([], '2026-01-01')).toBe(-1);
    });

    it('los ambientes salen en el orden en que aparecen, sin repetir ni vacíos', () => {
      const zoned = [capture('1', 'Cocina'), capture('2', null), capture('3', 'Fachada'), capture('4', 'Cocina')];
      expect(zonesOf(zoned)).toEqual(['Cocina', 'Fachada']);
    });
  });

});

describe('vista de la familia', () => {
  const stages = [
    aPublicStage('Preliminares', 1, 'COMPLETED', 100, { completedOn: '2026-02-12', plannedStart: '2026-02-02' }),
    aPublicStage('Revoques', 2, 'IN_PROGRESS', 40, { startedOn: '2026-08-16', plannedEnd: '2026-09-20' }),
    aPublicStage('Pintura', 3, 'PENDING', 0, { plannedStart: '2026-10-05' }),
  ];

  it('arma el camino con lo terminado, lo que está en curso y lo que viene, con fechas en palabras', () => {
    const journey = stageJourney(stages, '2026-09-14');

    expect(journey.map(step => step.state)).toEqual(['DONE', 'CURRENT', 'UPCOMING']);
    expect(journey[0].when).toBe('Terminó el 12 feb');
    expect(journey[1].when).toBe('En curso desde el 16 ago');
    expect(journey[2].when).toBe('Prevista para octubre de 2026');
    expect(journey[1].late).toBe(false);
    expect(stageJourney(stages, '2026-09-30')[1].late).toBe(true);
    expect(stagePosition(stages, '2026-09-14')).toEqual({ number: 2, total: 3 });
  });

  it('si nada está en curso, la primera pendiente es la actual; con todo terminado no hay posición', () => {
    const waiting = [aPublicStage('A', 1, 'COMPLETED', 100), aPublicStage('B', 2, 'PENDING', 0)];
    expect(stageJourney(waiting, '2026-09-14').map(step => step.state)).toEqual(['DONE', 'CURRENT']);
    expect(stagePosition([aPublicStage('A', 1, 'COMPLETED', 100)], '2026-09-14')).toBeNull();
  });

  it('la ficha muestra sólo lo cargado, en singular o plural', () => {
    const facts = houseFacts(publicDetails({ coveredAreaM2: 186, bedrooms: 3, bathrooms: 1, parkingSpaces: 0, orientation: 'Norte' }));
    expect(facts).toEqual([
      { label: 'Cubiertos', value: '186 m²' },
      { label: 'Dormitorios', value: '3' },
      { label: 'Baño', value: '1' },
      { label: 'Orientación', value: 'Norte' },
    ]);
  });

  it('abre el recorrido donde hay más fechas para viajar en el tiempo', () => {
    expect(busiestZone([capture('2026-04-12', 'Planta baja'), capture('2026-05-22', 'Planta baja'), capture('2026-09-05', 'Living')])).toBe('Planta baja');
    expect(busiestZone([capture('2026-04-12', 'Cocina'), capture('2026-09-05', 'Living')])).toBe('Living');
    expect(busiestZone([capture('2026-04-12')])).toBeNull();
  });

  it('junta los recorridos por día, del primero al último', () => {
    const moments = captureMoments([capture('2026-09-05'), capture('2026-04-12'), capture('2026-09-05', 'Club house')]);
    expect(moments.map(moment => [moment.date, moment.count])).toEqual([['2026-04-12', 1], ['2026-09-05', 2]]);
  });

  it('la portada nunca sale de la galería de venta: es material del modelo, no la obra', () => {
    const shot = photo('https://s/galeria.jpg', null);
    expect(heroImage(view({ showcase: [shot] }))).toBeNull();
  });
});

describe('historia por mes', () => {
  const entry = (date: string, description = date): PublicProgressEntry =>
    ({ date, stageName: null, description, source: 'MANUAL', corrected: false, photos: [], common: false, stageChange: null });

  it('agrupa avances y recorridos por mes, del más nuevo al más viejo', () => {
    const months = historyMonths(
      [entry('2026-09-02', 'primero del 2'), entry('2026-09-20'), entry('2026-09-02', 'segundo del 2'), entry('2026-07-01')],
      [capture('2026-10-05', 'Living'), capture('2026-09-05', 'Cocina'), capture('2026-09-06', 'Living')],
      ['Living', 'Cocina'],
    );

    expect(months.map(month => month.month)).toEqual(['2026-10', '2026-09', '2026-07']);
    expect(months[0].entries).toEqual([]);
    expect(months[0].sectors).toEqual(['Living']);
    expect(months[1].entries.map(item => item.description)).toEqual(['2026-09-20', 'primero del 2', 'segundo del 2']);
    expect(months[1].sectors).toEqual(['Living', 'Cocina']);
    expect(months[2].sectors).toEqual([]);
  });

  it('las capturas sin sector van como "General", igual que en el recorrido', () => {
    expect(historyMonths([], [capture('2026-09-05', 'Living'), capture('2026-09-06', null)])[0].sectors)
      .toEqual(['Living', 'General']);
  });

  it('sin avances ni recorridos no hay meses', () => {
    expect(historyMonths([], [])).toEqual([]);
  });

  it('la carga inicial va al final, "Antes del seguimiento", del más vieja a la más nueva, sin mezclarse con sus meses', () => {
    const initial = (date: string): PublicProgressEntry => ({ ...entry(date), source: 'INITIAL_LOAD' });
    const months = historyMonths(
      [entry('2026-10-14', 'del encargado'), initial('2025-03-07'), initial('2024-12-16'), initial('2025-11-14')],
      [capture('2026-10-05', 'Living')],
    );

    expect(months.map(month => month.month)).toEqual(['2026-10', 'antes']);
    expect(months[0].entries.map(item => item.description)).toEqual(['del encargado']);
    expect(months[1].beforeTracking).toBe(true);
    expect(months[1].entries.map(item => item.date)).toEqual(['2024-12-16', '2025-03-07', '2025-11-14']);
    expect(months[1].sectors).toEqual([]);
  });

  it('lo último de la obra es del seguimiento: la carga inicial no cuenta', () => {
    const initial: PublicProgressEntry = { ...entry('2026-10-20'), source: 'INITIAL_LOAD' };
    expect(lastUpdate({ timeline: [initial, entry('2026-10-14')] } as PublicUnitView)).toBe('2026-10-14');
    expect(lastUpdate({ timeline: [initial] } as PublicUnitView)).toBeNull();
  });

  it('nombra el mes como encabezado', () => {
    expect(monthHeading('2026-10')).toBe('Octubre 2026');
  });
});

describe('línea de tiempo mensual', () => {
  // Los datos de prueba de la Casa 1: meses parciales, con octubre completo.
  const living = [capture('2026-07-05', 'Living'), capture('2026-10-05', 'Living')];
  const cocina = [capture('2026-07-05', 'Cocina'), capture('2026-09-05', 'Cocina'), capture('2026-10-05', 'Cocina')];
  const dormitorio = [capture('2026-08-05', 'Dormitorio'), capture('2026-10-05', 'Dormitorio')];
  const segundo = [capture('2026-10-05', 'Segundo dormitorio')];

  it('un punto por mes con capturas, del más viejo al más nuevo', () => {
    expect(captureMonths([...cocina, ...living, ...dormitorio, ...segundo])).toEqual(['2026-07', '2026-08', '2026-09', '2026-10']);
  });

  it('un sector que no se volvió a grabar arrastra su última captura a los meses siguientes', () => {
    expect(captureAtMonth(living, '2026-08')).toBe(living[0]);
    expect(captureAtMonth(living, '2026-09')).toBe(living[0]);
    expect(captureAtMonth(cocina, '2026-08')).toBe(cocina[0]);
    expect(captureAtMonth(cocina, '2026-09')).toBe(cocina[1]);
    expect(captureAtMonth(living, '2026-10')).toBe(living[1]);
  });

  it('con dos capturas en el mismo mes muestra la más nueva, sin importar el orden en que llegan', () => {
    const late = capture('2026-09-25', 'Cocina');
    const early = capture('2026-09-02', 'Cocina');

    expect(captureAtMonth([late, early], '2026-09')).toBe(late);
    expect(captureAtMonth([early, late], '2026-09')).toBe(late);
  });

  it('un sector que todavía no tenía captura en ese mes no muestra nada', () => {
    expect(captureAtMonth(dormitorio, '2026-07')).toBeNull();
    expect(captureAtMonth(segundo, '2026-09')).toBeNull();
  });

  it('un mes anterior a la primera captura no muestra nada', () => {
    expect(captureAtMonth(cocina, '2026-06')).toBeNull();
    expect(captureAtMonth([], '2026-10')).toBeNull();
  });

  it('nombra los meses para la barra, el chip y el aviso', () => {
    expect(monthStop('2026-07')).toBe('Jul 2026');
    expect(shortMonth('2026-07')).toBe('jul');
    expect(longMonth('2026-09')).toBe('septiembre de 2026');
  });
});
