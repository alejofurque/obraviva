/**
 * Cuentas del editor de planos. Funciones puras: el editor dibuja en metros sobre la
 * imagen del plano, y todo lo que se guarda sale de acá.
 */

export interface Point {
  x: number;
  y: number;
}

/** Paso de ajuste al marcar un vértice: cinco centímetros, más fino que cualquier plano de venta. */
export const SNAP_M = 0.05;

export function snap(value: number, step = SNAP_M): number {
  return Math.round(Math.round(value / step) * step * 100) / 100;
}

/** Un vértice queda dentro de la planta y ajustado a la grilla fina. */
export function placePoint(point: Point, widthM: number, depthM: number): Point {
  return {
    x: snap(Math.min(widthM, Math.max(0, point.x))),
    y: snap(Math.min(depthM, Math.max(0, point.y))),
  };
}

/** Superficie de un polígono simple por la fórmula del área de Gauss, en m². */
export function polygonArea(points: Point[]): number {
  let twice = 0;
  for (let i = 0; i < points.length; i++) {
    const current = points[i];
    const next = points[(i + 1) % points.length];
    twice += current.x * next.y - next.x * current.y;
  }
  return Math.abs(twice) / 2;
}

/**
 * Profundidad real a partir del ancho que conoce el dueño y las proporciones de la imagen.
 * Así la imagen no se deforma: basta con saber cuánto mide la planta de lado a lado.
 */
export function depthFromImage(widthM: number, imageWidthPx: number, imageHeightPx: number): number {
  return Math.round((widthM * imageHeightPx / imageWidthPx) * 100) / 100;
}

export function toFlat(points: Point[]): number[] {
  return points.flatMap(point => [point.x, point.y]);
}

export function toPoints(flat: number[]): Point[] {
  const points: Point[] = [];
  for (let i = 0; i + 1 < flat.length; i += 2) {
    points.push({ x: flat[i], y: flat[i + 1] });
  }
  return points;
}

export function svgPoints(points: Point[]): string {
  return points.map(point => `${point.x},${point.y}`).join(' ');
}
