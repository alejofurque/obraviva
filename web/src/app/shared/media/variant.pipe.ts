import { Pipe, PipeTransform } from '@angular/core';

/**
 * Una foto o video con sus versiones livianas, como llegan del backend: el original en
 * `url` (vistas del comprador y Novedades) o en `downloadUrl` (pantallas del dueño), y la
 * miniatura (480 px) y la de pantalla (1600 px) nulas mientras el worker no las genera.
 */
export interface WithVariants {
  url?: string | null;
  downloadUrl?: string | null;
  thumbUrl: string | null;
  screenUrl: string | null;
}

export type Variant = 'thumb' | 'screen' | 'poster';

/**
 * La versión que corresponde a cada uso. Con el original como respaldo: una foto recién
 * subida, o cuyas versiones fallaron, se sigue viendo. Un póster de video no tiene
 * respaldo: sin fotograma, el video muestra el suyo.
 */
export function variantOf(media: WithVariants, variant: Variant): string | null {
  const original = media.url ?? media.downloadUrl ?? null;
  switch (variant) {
    case 'thumb':
      return media.thumbUrl ?? original;
    case 'screen':
      return media.screenUrl ?? original;
    default:
      return media.screenUrl ?? media.thumbUrl ?? null;
  }
}

/** En plantillas: `[src]="foto | variant: 'thumb'"`. */
@Pipe({ name: 'variant' })
export class VariantPipe implements PipeTransform {
  transform(media: WithVariants, variant: Variant): string | null {
    return variantOf(media, variant);
  }
}

/**
 * Una portada o un render, que llegan con sus versiones al lado: `coverUrl`, `coverThumbUrl` y
 * `coverScreenUrl`, o lo mismo con `render`. Viven fuera de los medios de obra y así las manda
 * el servidor; la regla es la misma: la versión que corresponde o, si no está, el original.
 */
export type ImagePrefix = 'cover' | 'render';
type WithPrefixedVariants<P extends ImagePrefix> = { [K in `${P}Url` | `${P}ThumbUrl` | `${P}ScreenUrl`]?: string | null };

export function imageVariantOf<P extends ImagePrefix>(
  item: WithPrefixedVariants<P> | null | undefined, prefix: P, variant: 'thumb' | 'screen',
): string | null {
  if (!item) return null;
  const fields = item as Record<string, string | null | undefined>;
  return variantOf({
    url: fields[`${prefix}Url`] ?? null,
    thumbUrl: fields[`${prefix}ThumbUrl`] ?? null,
    screenUrl: fields[`${prefix}ScreenUrl`] ?? null,
  }, variant);
}

/** En plantillas: `[src]="casa | image: 'cover' : 'thumb'"`. */
@Pipe({ name: 'image' })
export class ImagePipe implements PipeTransform {
  transform<P extends ImagePrefix>(item: WithPrefixedVariants<P> | null | undefined, prefix: P, variant: 'thumb' | 'screen'): string | null {
    return imageVariantOf(item, prefix, variant);
  }
}
