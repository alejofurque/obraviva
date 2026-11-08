import {
  ConfidenceLevel,
  DevelopmentType,
  ExtractionReviewStatus,
  ReportStatus,
  SetupStep,
  SetupStepKey,
  UnitSalesStatus,
} from '../../core/api/api.models';
import { formatWholeMoney } from '../../shared/money/money';

/** Estado comercial en palabras. Es interno: nunca se muestra al comprador. */
export const SALES_STATUS_LABELS: Record<UnitSalesStatus, string> = {
  AVAILABLE: 'Disponible',
  RESERVED: 'Reservada',
  SOLD: 'Vendida',
};

export const SALES_STATUS_OPTIONS: UnitSalesStatus[] = ['AVAILABLE', 'RESERVED', 'SOLD'];

/**
 * Pestaña del desarrollo donde se resuelve cada paso de su puesta en marcha. Lo que es de
 * cada casa (etapas, ficha, avances, recorridos, compradores) se resuelve desde Casas.
 */
export function developmentStepPath(key: SetupStepKey): string {
  switch (key) {
    case 'DETAILS':
      return 'datos';
    case 'TEAM':
      return 'equipo';
    default:
      return 'casas';
  }
}

/**
 * Los pasos que son datos que el dueño carga, del desarrollo y de cada casa. El primer
 * avance, el primer recorrido y los compradores llegan con la obra y la venta: contarlos
 * haría que todo desarrollo o casa nueva dijera que le faltan datos hasta grabar su primer
 * recorrido.
 */
const DATA_STEPS: ReadonlySet<SetupStepKey> = new Set<SetupStepKey>([
  'DETAILS', 'UNITS', 'STAGES', 'SHEETS', 'TEAM', 'SHEET', 'GALLERY', 'PRICE', 'SECTORS',
]);

/** El primer dato que falta, en el orden en que los da el servidor; nulo si no falta ninguno. */
export function firstMissingData(steps: SetupStep[] | null): SetupStep | null {
  return (steps ?? []).find(step => !step.done && DATA_STEPS.has(step.key)) ?? null;
}

/** Pestaña de la casa donde se resuelve cada paso de su puesta en marcha. */
export function houseStepPath(key: SetupStepKey): string {
  switch (key) {
    case 'SHEET':
    case 'GALLERY':
    case 'SHEETS':
    case 'DETAILS':
      return 'ficha';
    case 'PRICE':
    case 'BUYER':
    case 'UNITS':
      return 'venta';
    case 'STAGES':
      return 'etapas';
    case 'FIRST_ENTRY':
      return 'historia';
    case 'FIRST_CAPTURE':
    case 'SECTORS':
      return 'recorridos';
    case 'TEAM':
      return '';
  }
}

export const DEVELOPMENT_TYPE_LABELS: Record<DevelopmentType, string> = {
  BARRIO_CERRADO: 'Barrio cerrado',
  BARRIO_ABIERTO: 'Barrio abierto',
  COMPLEJO: 'Complejo de casas',
  LOTEO: 'Loteo',
};

export const DEVELOPMENT_TYPE_OPTIONS: DevelopmentType[] = ['BARRIO_CERRADO', 'BARRIO_ABIERTO', 'COMPLEJO', 'LOTEO'];

/** Hacia dónde da el frente o el contrafrente: en una casa, define la luz. */
export const ORIENTATIONS = ['Norte', 'Noreste', 'Este', 'Sudeste', 'Sur', 'Sudoeste', 'Oeste', 'Noroeste'];

/** Un precio de lista, sin decimales y en la moneda del sistema: "US$ 189.000". */
export function formatPrice(amount: number | null): string | null {
  return amount === null ? null : formatWholeMoney(amount);
}

/** "1 foto seleccionada", "3 fotos seleccionadas": el número con su palabra en singular o plural. */
export function countLabel(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

/** "2027-06" a "junio de 2027". */
export function yearMonthLabel(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const [year, month] = value.split('-');
  const names = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto',
    'septiembre', 'octubre', 'noviembre', 'diciembre'];
  return `${names[Number(month) - 1]} de ${year}`;
}

/** Estado de un parte por audio, en palabras de obra. */
export const EXTRACTION_STATUS_LABELS: Record<ExtractionReviewStatus, string> = {
  PENDING: 'Procesando',
  READY_FOR_REVIEW: 'Espera revisión',
  APPROVED: 'Publicado',
  REJECTED: 'Descartado',
  FAILED: 'Falló el procesamiento',
};

export const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  PENDING: 'Redactando',
  READY_FOR_REVIEW: 'Espera revisión',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  FAILED: 'Falló la redacción',
};

/** La confianza baja se dice como consejo: es lo que el revisor tiene que mirar primero. */
export const CONFIDENCE_LABELS: Record<ConfidenceLevel, string> = {
  HIGH: 'Confianza alta',
  MEDIUM: 'Confianza media',
  LOW: 'Confianza baja, conviene verificar',
};
