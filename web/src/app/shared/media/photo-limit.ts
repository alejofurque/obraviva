/**
 * Fotos por avance. Es el mismo tope del servidor: el pedido de publicación (`@Size`) y la subida
 * de una foto a un avance que ya existe ("Agregar fotos"), que cuenta las de todas sus versiones.
 */
export const MAX_PHOTOS_PER_ENTRY = 10;

/** Cuántas fotos más admite un avance que ya tiene `photos`; nunca menos de cero. */
export function photoRoom(photos: number): number {
  return Math.max(0, MAX_PHOTOS_PER_ENTRY - photos);
}
