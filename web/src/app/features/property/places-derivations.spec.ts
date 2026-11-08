import { PublicFront, PublicPlace } from '../../core/api/api.models';
import { aPlaceMonth, aPublicMedia } from '../../testing/fixtures';
import {
  coverMissing, frontMissing, frontOptions, liveCover, mediaCount, mediaDay, monthsChronological, placeMediaAt, placeTour,
  placesWithMaterial,
} from './places-derivations';

const front = (overrides: Partial<PublicFront> = {}): PublicFront => ({
  sector: 'Frente',
  latest: { url: 'https://s/hoy.jpg', thumbUrl: null, screenUrl: null, date: '2026-10-14' },
  first: { url: 'https://s/inicio.jpg', thumbUrl: null, screenUrl: null, date: '2026-03-02' },
  renderUrl: 'https://s/render.jpg', renderThumbUrl: null, renderScreenUrl: null,
  project: { renderUrl: 'https://s/render.jpg', renderScreenUrl: null, photoUrl: 'https://s/hoy.jpg', photoScreenUrl: null, photoDate: '2026-10-14' },
  ...overrides,
});
const empty: PublicFront = { sector: null, latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null };

describe('Portada viva', () => {
  it('es la última foto del frente, con su lugar y su día', () => {
    expect(liveCover(front())).toEqual({ url: 'https://s/hoy.jpg', date: '2026-10-14', sector: 'Frente' });
  });

  it('con la versión de pantalla lista, la portada usa esa y no el original', () => {
    const latest = { url: 'https://s/hoy.png', thumbUrl: 'https://s/hoy-mini.jpg', screenUrl: 'https://s/hoy-pantalla.jpg', date: '2026-10-14' };
    expect(liveCover(front({ latest }))?.url).toBe('https://s/hoy-pantalla.jpg');
  });

  it('sin foto del frente, o sin frente marcado, no hay portada viva', () => {
    expect(liveCover(front({ latest: null, first: null }))).toBeNull();
    expect(liveCover(empty)).toBeNull();
    expect(liveCover(null)).toBeNull();
  });
});

describe('Tu casa, lugar por lugar', () => {
  const place = (months: PublicPlace['months']): PublicPlace => ({ name: 'Living', months });

  it('los meses van del más viejo al más nuevo, como una línea de tiempo', () => {
    const months = monthsChronological(place([aPlaceMonth('2026-07'), aPlaceMonth('2026-10'), aPlaceMonth('2026-08')]));
    expect(months.map(item => item.month)).toEqual(['2026-07', '2026-08', '2026-10']);
  });

  it('un lugar sin fotos, videos ni 3D no se ofrece, tampoco General', () => {
    const places: PublicPlace[] = [
      { name: 'Living', months: [aPlaceMonth('2026-10', { tour: true })] },
      { name: 'General', months: [aPlaceMonth('2026-10')] },
    ];
    expect(placesWithMaterial(places).map(item => item.name)).toEqual(['Living']);
  });

  it('cuenta fotos y videos en una línea', () => {
    expect(mediaCount({ photos: [aPublicMedia('a'), aPublicMedia('b')], videos: [aPublicMedia('c')] })).toBe('2 fotos · 1 video');
    expect(mediaCount({ photos: [aPublicMedia('a')], videos: [] })).toBe('1 foto');
    expect(mediaCount({ photos: [], videos: [] })).toBe('Sin fotos ni videos');
  });

  it('con recorrido ese mes se ofrece; sin él, el anterior más cercano', () => {
    const living = place([aPlaceMonth('2026-07', { tour: true }), aPlaceMonth('2026-08', { tour: true }), aPlaceMonth('2026-10')]);

    expect(placeTour(living, '2026-08')).toEqual({ kind: 'tour' });
    expect(placeTour(living, '2026-10')).toEqual({ kind: 'missed', previous: '2026-08' });
  });

  it('si los recorridos son todos posteriores, avisa sin ofrecer uno anterior', () => {
    expect(placeTour(place([aPlaceMonth('2026-07'), aPlaceMonth('2026-09', { tour: true })]), '2026-07'))
      .toEqual({ kind: 'missed', previous: null });
  });

  it('un lugar que nunca tuvo recorrido se sigue con fotos y videos', () => {
    expect(placeTour(place([aPlaceMonth('2026-10', { photos: [aPublicMedia('a')] })]), '2026-10')).toEqual({ kind: 'photos-only' });
  });

  it('el día de una foto es el del avance, si no el de la sesión, si no el de la subida', () => {
    expect(mediaDay(aPublicMedia('a', { entryDate: '2026-10-01', takenOn: '2026-10-05' }))).toBe('2026-10-01');
    expect(mediaDay(aPublicMedia('a', { takenOn: '2026-10-05' }))).toBe('2026-10-05');
    expect(mediaDay(aPublicMedia('a', { uploadedAt: '2026-10-07T12:00:00Z' }))).toBe('2026-10-07');
  });

  it('la hoja del visor toma lo del lugar y el mes, y nada si ese mes no tiene fotos ni videos', () => {
    const places: PublicPlace[] = [
      { name: 'Living', months: [aPlaceMonth('2026-10', { photos: [aPublicMedia('a')] }), aPlaceMonth('2026-09', { tour: true })] },
    ];

    expect(placeMediaAt(places, 'Living', '2026-10')?.photos.map(item => item.url)).toEqual(['a']);
    expect(placeMediaAt(places, 'Living', '2026-09')).toBeNull();
    expect(placeMediaAt(places, 'Baño', '2026-10')).toBeNull();
  });
});

describe('Tu casa, de frente', () => {
  it('El proyecto compara lo que decidió el servidor; Cuando empezó, la primera foto contra la de hoy', () => {
    expect(frontOptions(front()).map(option => [option.key, option.label, option.beforeUrl, option.afterUrl])).toEqual([
      ['project', 'El proyecto', 'https://s/render.jpg', 'https://s/hoy.jpg'],
      ['start', 'Cuando empezó', 'https://s/inicio.jpg', 'https://s/hoy.jpg'],
    ]);
  });

  it('El proyecto compara la versión de pantalla del render cuando está lista', () => {
    const ready = front({ project: { renderUrl: 'https://s/render.png', renderScreenUrl: 'https://s/render-pantalla.jpg', photoUrl: 'https://s/hoy.jpg', photoScreenUrl: 'https://s/hoy-pantalla.jpg', photoDate: '2026-10-14' } });
    expect(frontOptions(ready)[0]).toEqual(expect.objectContaining({ beforeUrl: 'https://s/render-pantalla.jpg', afterUrl: 'https://s/hoy-pantalla.jpg' }));
  });

  it('la comparación vieja de la casa sirve sin frente marcado ni fotos del frente', () => {
    const old = { ...empty, project: { renderUrl: 'https://s/viejo.jpg', renderScreenUrl: null, photoUrl: 'https://s/avance.jpg', photoScreenUrl: null, photoDate: '2026-10-09' } };
    expect(frontOptions(old).map(option => [option.beforeUrl, option.afterUrl, option.afterDate]))
      .toEqual([['https://s/viejo.jpg', 'https://s/avance.jpg', '2026-10-09']]);
  });

  it('con una sola opción queda esa; sin ninguna, no hay nada que comparar', () => {
    expect(frontOptions(front({ project: null })).map(option => option.key)).toEqual(['start']);
    expect(frontOptions(front({ first: null })).map(option => option.key)).toEqual(['project']);
    expect(frontOptions(front({ first: null, project: null }))).toEqual([]);
    expect(frontOptions(null)).toEqual([]);
  });
});

describe('Qué falta, para "Ver como el comprador"', () => {
  it('con la sección completa, nada', () => {
    expect(frontMissing(front())).toEqual([]);
    expect(coverMissing(front())).toEqual([]);
  });

  it('sin frente marcado: marcarlo; y el render si no está', () => {
    expect(frontMissing(empty)).toEqual(['front', 'render']);
    expect(coverMissing(empty)).toEqual(['front']);
  });

  it('con el frente marcado y sin fotos: las fotos, aunque el render ya esté', () => {
    const noPhotos = front({ latest: null, first: null, project: null });
    expect(frontMissing(noPhotos)).toEqual(['photos']);
    expect(coverMissing(noPhotos)).toEqual(['photos']);
  });

  it('con foto de hoy y sin render ni comparación vieja: sólo el render', () => {
    expect(frontMissing(front({ first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null }))).toEqual(['render']);
  });

  it('si la comparación vieja ya muestra la sección, no hay aviso', () => {
    expect(frontMissing({ ...empty, project: { renderUrl: 'r', renderScreenUrl: null, photoUrl: 'p', photoScreenUrl: null, photoDate: '2026-10-09' } })).toEqual([]);
  });
});
