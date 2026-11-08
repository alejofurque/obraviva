/**
 * El último lugar elegido para una foto en una casa, para precargar el chip de la próxima.
 * Lo comparten "Publicar avance" y las fotos de la revisión de un audio: el encargado que
 * está en el frente sigue en el frente aunque cambie de pantalla. Es de cada persona: en un
 * teléfono compartido, el lugar de uno no le aparece al otro. Sin usuario no se recuerda.
 */
function sectorKey(userId: string, unitId: string): string {
  return `obraviva.field.sector.${userId}.${unitId}`;
}

/** El último lugar elegido, si sigue siendo un sector de la casa; si no, ninguno. */
export function lastSector(userId: string | null | undefined, unitId: string, sectors: string[]): string | null {
  if (!userId) {
    return null;
  }
  try {
    const last = localStorage.getItem(sectorKey(userId, unitId));
    return last && sectors.includes(last) ? last : null;
  } catch {
    return null;
  }
}

/** Recuerda el lugar elegido; sin lugar, se olvida. Sin almacenamiento, no pasa nada. */
export function rememberSector(userId: string | null | undefined, unitId: string, sector: string | null): void {
  if (!userId) {
    return;
  }
  try {
    if (sector) {
      localStorage.setItem(sectorKey(userId, unitId), sector);
    } else {
      localStorage.removeItem(sectorKey(userId, unitId));
    }
  } catch {
    // Sin almacenamiento, el chip arranca vacío la próxima vez.
  }
}
