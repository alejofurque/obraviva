import { variantOf, imageVariantOf } from './variant.pipe';

describe('Versiones livianas de una foto', () => {
  const ready = { url: 'original.png', thumbUrl: 'mini.jpg', screenUrl: 'pantalla.jpg' };
  const pending = { url: 'original.png', thumbUrl: null, screenUrl: null };

  it('cada uso toma su versión: miniatura en las grillas, pantalla en lo grande', () => {
    expect(variantOf(ready, 'thumb')).toBe('mini.jpg');
    expect(variantOf(ready, 'screen')).toBe('pantalla.jpg');
  });

  it('sin versiones todavía, o si fallaron, se ve el original', () => {
    expect(variantOf(pending, 'thumb')).toBe('original.png');
    expect(variantOf(pending, 'screen')).toBe('original.png');
  });

  it('en las pantallas del dueño el original viene en downloadUrl', () => {
    expect(variantOf({ downloadUrl: 'original.png', thumbUrl: null, screenUrl: null }, 'thumb')).toBe('original.png');
  });

  it('el póster de un video es su fotograma, y sin fotograma no hay póster: nunca el video mismo', () => {
    expect(variantOf({ url: 'video.mp4', thumbUrl: 'cuadro-mini.jpg', screenUrl: 'cuadro.jpg' }, 'poster')).toBe('cuadro.jpg');
    expect(variantOf({ url: 'video.mp4', thumbUrl: null, screenUrl: null }, 'poster')).toBeNull();
  });
});

describe('Portadas y renders', () => {
  const cover = { coverUrl: 'portada.png', coverThumbUrl: 'portada-mini.jpg', coverScreenUrl: 'portada-pantalla.jpg' };

  it('traen sus versiones al lado, con el nombre de la imagen adelante', () => {
    expect(imageVariantOf(cover, 'cover', 'thumb')).toBe('portada-mini.jpg');
    expect(imageVariantOf(cover, 'cover', 'screen')).toBe('portada-pantalla.jpg');
    expect(imageVariantOf({ renderUrl: 'render.png', renderThumbUrl: null, renderScreenUrl: 'render-pantalla.jpg' }, 'render', 'screen'))
      .toBe('render-pantalla.jpg');
  });

  it('sin versiones todavía se ve el original; sin imagen, nada', () => {
    expect(imageVariantOf({ coverUrl: 'portada.png', coverThumbUrl: null, coverScreenUrl: null }, 'cover', 'thumb')).toBe('portada.png');
    expect(imageVariantOf({ coverUrl: null, coverThumbUrl: null, coverScreenUrl: null }, 'cover', 'screen')).toBeNull();
    expect(imageVariantOf(null, 'render', 'screen')).toBeNull();
  });
});
