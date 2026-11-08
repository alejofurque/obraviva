import { SalesSheetPayload, Unit } from '../../core/api/api.models';

/** Texto separado por comas, como lo escribe una persona, a lista limpia y sin repetidos. */
export function parseFeatures(text: string): string[] {
  const seen = new Set<string>();
  const features: string[] = [];
  for (const raw of text.split(',')) {
    const feature = raw.trim();
    if (feature && !seen.has(feature.toLowerCase())) {
      seen.add(feature.toLowerCase());
      features.push(feature);
    }
  }
  return features;
}

/** Número opcional de un campo de formulario: vacío es nulo, no cero. */
export function optionalNumber(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** Texto opcional: vacío o sólo espacios es nulo. */
export function optionalText(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim();
  return trimmed === '' ? null : trimmed;
}

/**
 * Ficha de venta completa de una casa con algunos campos cambiados. El servidor reemplaza
 * la ficha entera: guardar la descripción sin mandar el precio lo borraría, así que se
 * parte siempre de lo que la casa ya tiene.
 */
export function salesSheetOf(unit: Unit, changes: Partial<SalesSheetPayload> = {}): SalesSheetPayload {
  return {
    modelName: unit.modelName,
    lotAreaM2: unit.lotAreaM2,
    coveredAreaM2: unit.coveredAreaM2,
    semiCoveredAreaM2: unit.semiCoveredAreaM2,
    rooms: unit.rooms,
    toilettes: unit.toilettes,
    floors: unit.floors,
    parkingSpaces: unit.parkingSpaces,
    orientation: unit.orientation,
    longDescription: unit.longDescription,
    finishes: unit.finishes,
    listPrice: unit.listPrice,
    priceCurrency: unit.priceCurrency,
    ...changes,
  };
}
