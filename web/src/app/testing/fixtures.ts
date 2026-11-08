import {
  ActivityItem,
  Development,
  MediaAsset,
  ProgressEntry,
  PublicDevelopment,
  PublicMedia,
  PublicPlaceMonth,
  PublicProgressEntry,
  PublicStage,
  PublicUnitDetails,
  ShareLinkSummary,
  Stage,
  StageStatus,
  Unit,
} from '../core/api/api.models';

/**
 * Datos de prueba completos para los specs. Cada uno arma el objeto entero con valores
 * vacíos y deja pisar lo que el test necesita: un campo nuevo en el modelo se agrega acá
 * una vez, no en cada spec.
 */

export function aShareLink(overrides: Partial<ShareLinkSummary> = {}): ShareLinkSummary {
  return {
    id: 'l1', label: 'Familia', active: true, expiresAt: null, revokedAt: null, lastAccessedAt: null, accessCount: 0,
    createdAt: '2026-10-01T12:00:00Z', createdByName: null, createdByYou: false,
    ...overrides,
  };
}

export function aDevelopment(overrides: Partial<Development> = {}): Development {
  return {
    id: 'd1', organizationId: 'o1', name: 'Los Aromos', address: null, kind: 'MULTI_UNIT', role: 'OWNER',
    overallProgressPercent: 0, currentStageName: null, unitCount: 0, completedUnitCount: 0,
    description: null, locality: null, province: null, developmentType: null, landAreaM2: null, startedOn: null,
    estimatedDelivery: null, amenities: [], services: [], architect: null, builder: null, permitNumber: null,
    coverUrl: null, coverThumbUrl: null, coverScreenUrl: null, createdAt: '2026-01-01T00:00:00Z', restrictedToUnitIds: null,
    ...overrides,
  };
}

export function aUnit(overrides: Partial<Unit> = {}): Unit {
  return {
    id: 'u1', developmentId: 'd1', code: 'Casa 1', description: null, areaM2: null, bedrooms: null, bathrooms: null,
    features: [], estimatedDelivery: null, hasCover: false, salesStatus: 'AVAILABLE', salesStatusOn: null, buyerCount: 0,
    modelName: null, lotAreaM2: null, coveredAreaM2: null, semiCoveredAreaM2: null, rooms: null, toilettes: null,
    floors: null, parkingSpaces: null, orientation: null, longDescription: null, finishes: null,
    listPrice: null, priceCurrency: null, progressPercent: 0, currentStageName: null, stageCount: 0,
    createdAt: '2026-01-01T00:00:00Z', coverUrl: null, coverThumbUrl: null, coverScreenUrl: null,
    ...overrides,
  };
}

export function aStage(overrides: Partial<Stage> = {}): Stage {
  return {
    id: 's1', developmentId: 'd1', unitId: null, name: 'Estructura', description: null, orderIndex: 1,
    status: 'IN_PROGRESS', progressPercent: 40, weight: 1, budgetAmount: null, plannedStart: null, plannedEnd: null,
    startedOn: null, completedOn: null, createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

export function aMedia(overrides: Partial<MediaAsset> = {}): MediaAsset {
  return {
    id: 'm1', developmentId: 'd1', progressEntryId: 'e1', progressRootId: 'e1', unitId: null, originalFilename: 'a.jpg',
    contentType: 'image/jpeg', kind: 'PHOTO', status: 'AVAILABLE', downloadUrl: 'https://s/a.jpg', mine: false,
    createdAt: '2026-09-10T10:00:00Z', sector: null, showcase: false, takenOn: null, thumbUrl: null, screenUrl: null,
    ...overrides,
  };
}

export function anEntry(overrides: Partial<ProgressEntry> = {}): ProgressEntry {
  return {
    id: 'e1', developmentId: 'd1', unitId: 'u1', stageId: 's1', entryDate: '2026-10-01', description: 'Se avanzó.',
    source: 'MANUAL', version: 1, rootId: 'e1', current: true, createdAt: '2026-10-01T12:00:00Z',
    authorName: null, stageChange: null,
    ...overrides,
  };
}

export function aPublicMedia(url: string, overrides: Partial<PublicMedia> = {}): PublicMedia {
  return {
    url, contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-09-10T15:00:00Z', entryDate: null, stageName: null,
    takenOn: null, thumbUrl: null, screenUrl: null,
    ...overrides,
  };
}

export function aPlaceMonth(month: string, overrides: Partial<PublicPlaceMonth> = {}): PublicPlaceMonth {
  return { month, photos: [], videos: [], tour: false, notes: [], ...overrides };
}

export function aPublicStage(name: string, orderIndex: number, status: StageStatus, progressPercent: number,
                             overrides: Partial<PublicStage> = {}): PublicStage {
  return {
    name, orderIndex, status, progressPercent, description: null, plannedStart: null, plannedEnd: null,
    startedOn: null, completedOn: null,
    ...overrides,
  };
}

/** Los sectores estándar de la plantilla de casa. */
export const STANDARD_SECTORS = ['Frente', 'Living y cocina', 'Dormitorio principal', 'Baño'];

export function publicDetails(overrides: Partial<PublicUnitDetails> = {}): PublicUnitDetails {
  return {
    areaM2: null, bedrooms: null, bathrooms: null, features: [], estimatedDelivery: null, coverUrl: null, coverThumbUrl: null, coverScreenUrl: null,
    modelName: null, lotAreaM2: null, coveredAreaM2: null, semiCoveredAreaM2: null, rooms: null, toilettes: null,
    floors: null, parkingSpaces: null, orientation: null, longDescription: null, finishes: null,
    ...overrides,
  };
}

export function publicDevelopment(overrides: Partial<PublicDevelopment> = {}): PublicDevelopment {
  return {
    description: null, locality: null, province: null, developmentType: null, landAreaM2: null, amenities: [],
    services: [], architect: null, builder: null, estimatedDelivery: null, coverUrl: null, coverThumbUrl: null, coverScreenUrl: null,
    ...overrides,
  };
}

export function aPublicEntry(overrides: Partial<PublicProgressEntry> = {}): PublicProgressEntry {
  return {
    date: '2026-09-10', stageName: null, description: 'Avance', source: 'MANUAL', corrected: false, photos: [],
    common: false, stageChange: null,
    ...overrides,
  };
}

/** Un ítem mínimo; cada test pisa lo que necesita. */
export function anActivityItem(overrides: Partial<ActivityItem> = {}): ActivityItem {
  return {
    kind: 'ENTRY', id: 'entry:r1', at: '2026-10-20T15:00:00Z', unitId: 'u1', unitCode: 'Casa 1', authorId: 'p1',
    authorName: 'Carlos Medina', unseen: null, session: null, capture: null, supply: null,
    entry: {
      entryId: 'e2', rootId: 'r1', entryDate: '2026-10-20', stageName: 'Instalación eléctrica', source: 'MANUAL',
      description: 'Cableado de planta baja', correctedByName: null, correctedAt: null, photos: [], stageChange: null,
    },
    ...overrides,
  };
}
