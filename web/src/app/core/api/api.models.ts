export interface Organization {
  id: string;
  name: string;
  createdAt: string;
}

/** Rol del usuario en la organización dueña: OWNER es el desarrollista, MEMBER el encargado. */
export type OrganizationRole = 'OWNER' | 'MEMBER';

/**
 * Obra única (una casa, un galpón: la obra es lo que se entrega) o edificio con unidades
 * que se venden por separado. Una obra única tiene una unidad interna que no se muestra.
 */
export type DevelopmentKind = 'SINGLE' | 'MULTI_UNIT';

/** Qué tipo de desarrollo es, para contarlo al comprador. */
export type DevelopmentType = 'BARRIO_CERRADO' | 'BARRIO_ABIERTO' | 'COMPLEJO' | 'LOTEO';

/**
 * Un desarrollo de casas: el barrio o complejo, con sus casas (cada una una obra propia)
 * y sus obras comunes.
 */
export interface Development {
  id: string;
  organizationId: string;
  name: string;
  address: string | null;
  kind: DevelopmentKind;
  role: OrganizationRole;
  /** Promedio del avance de sus casas; sin casas con etapas, el de las obras comunes. */
  overallProgressPercent: number;
  /** Etapa en curso de las obras comunes. */
  currentStageName: string | null;
  unitCount: number;
  completedUnitCount: number;
  description: string | null;
  locality: string | null;
  province: string | null;
  developmentType: DevelopmentType | null;
  landAreaM2: number | null;
  startedOn: string | null;
  /** Año y mes, por ejemplo 2027-06. */
  estimatedDelivery: string | null;
  amenities: string[];
  services: string[];
  architect: string | null;
  builder: string | null;
  permitNumber: string | null;
  coverUrl: string | null;
  coverThumbUrl: string | null;
  coverScreenUrl: string | null;
  createdAt: string;
  /**
   * Nulo: trabaja en todo el desarrollo. Con valor, son las únicas casas en las que carga
   * (encargado de casas puntuales), y unitCount cuenta esas casas.
   */
  restrictedToUnitIds: string[] | null;
}

export interface CreateDevelopmentPayload {
  organizationId: string;
  name: string;
  address?: string;
  kind: DevelopmentKind;
}

export interface UpdateDevelopmentPayload {
  name: string;
  address: string | null;
  description: string | null;
  locality: string | null;
  province: string | null;
  developmentType: DevelopmentType | null;
  landAreaM2: number | null;
  startedOn: string | null;
  estimatedDelivery: string | null;
  amenities: string[];
  services: string[];
  architect: string | null;
  builder: string | null;
  permitNumber: string | null;
}

export type StageStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'DELAYED';

export interface Stage {
  id: string;
  developmentId: string;
  /** Casa de la etapa; nulo si es de las obras comunes. */
  unitId: string | null;
  name: string;
  description: string | null;
  orderIndex: number;
  status: StageStatus;
  progressPercent: number;
  weight: number;
  /** Nulo para el encargado: el presupuesto es información económica. */
  budgetAmount: number | null;
  plannedStart: string | null;
  plannedEnd: string | null;
  /** Se completan solas al arrancar y al completar la etapa. */
  startedOn: string | null;
  completedOn: string | null;
  createdAt: string;
}

export interface Vector3 {
  x: number;
  y: number;
  z: number;
}

export interface CaptureTransformDto {
  position: Vector3;
  rotationDeg: Vector3;
  scale: number;
}

export interface Capture3d {
  id: string;
  developmentId: string;
  unitId: string | null;
  stageId: string | null;
  captureDate: string;
  description: string | null;
  zone: string | null;
  status: 'PENDING' | 'AVAILABLE';
  transform: CaptureTransformDto;
  splatUrl: string | null;
}

// --- Reportes de período ---

/**
 * PENDING: el snapshot está compuesto y el reporte espera al redactor.
 * READY_FOR_REVIEW: hay texto y espera decisión humana.
 * APPROVED / REJECTED: resuelto, no vuelve atrás.
 * FAILED: la redacción agotó sus reintentos.
 */
export type ReportStatus =
  | 'PENDING'
  | 'READY_FOR_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED';

export interface ReportStageState {
  id: string;
  name: string;
  orderIndex: number;
  /**
   * Estado de la etapa al momento de componerse el reporte, no al cierre del
   * período: el modelo de datos no versiona el estado de las etapas. El nombre del
   * campo es literal a propósito.
   */
  statusAtCompositionTime: string;
}

export interface ReportProgressItem {
  date: string;
  stageName: string | null;
  description: string;
  source: ProgressSource;
}

export interface ReportCaptureItem {
  id: string;
  date: string;
  description: string | null;
}

export interface ReportTotals {
  progressEntries: number;
  captures: number;
  stagesCompleted: number;
  stagesInProgress: number;
  stagesDelayed: number;
  stagesPending: number;
}

/** Snapshot congelado del período. Todas las cifras salen del motor de datos. */
export interface ReportData {
  developmentName: string;
  developmentAddress: string | null;
  periodStart: string;
  periodEnd: string;
  stages: ReportStageState[];
  progressEntries: ReportProgressItem[];
  captures: ReportCaptureItem[];
  totals: ReportTotals;
}

export interface ReportDetail {
  id: string;
  developmentId: string;
  status: ReportStatus;
  periodStart: string;
  periodEnd: string;
  data: ReportData;
  narrative: string | null;
  promptVersion: string | null;
  modelId: string | null;
  createdAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNotes: string | null;
}

export interface ReportSummary {
  id: string;
  developmentId: string;
  status: ReportStatus;
  periodStart: string;
  periodEnd: string;
  progressEntries: number;
  captures: number;
  createdAt: string;
}

export interface GenerateReportPayload {
  periodStart: string;
  periodEnd: string;
}

export interface ApproveReportPayload {
  narrative: string;
  notes?: string;
}

export interface RejectReportPayload {
  notes?: string;
}

// --- Interpretación de audios ---

/**
 * PENDING: el audio se registró y espera o atraviesa el procesamiento.
 * READY_FOR_REVIEW: hay propuesta y espera confirmación humana.
 * APPROVED / REJECTED: resuelta, no vuelve atrás.
 * FAILED: la transcripción o la interpretación agotaron sus reintentos.
 */
export type ExtractionReviewStatus =
  | 'PENDING'
  | 'READY_FOR_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED';

/**
 * HIGH: el audio lo dice explícitamente.
 * MEDIUM: se desprende, pero admite otra lectura.
 * LOW: interpretación dudosa; el revisor debería verificarla.
 */
export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';

export interface StageProposal {
  stageId: string | null;
  proposedStatus: string | null;
  /** Fragmento del audio que sustenta la propuesta, para contrastarla. */
  rationale: string | null;
  confidence: ConfidenceLevel;
}

export interface ProgressNote {
  stageId: string | null;
  description: string;
  confidence: ConfidenceLevel;
}

export interface SupplyAlert {
  description: string;
  confidence: ConfidenceLevel;
}

export interface ExtractionProposal {
  stageProposals: StageProposal[];
  progressNotes: ProgressNote[];
  supplyAlerts: SupplyAlert[];
}

export interface ExtractionDetail {
  id: string;
  developmentId: string;
  reviewStatus: ExtractionReviewStatus;
  transcript: string | null;
  proposal: ExtractionProposal | null;
  promptVersion: string | null;
  modelId: string | null;
  audioUrl: string | null;
  createdAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNotes: string | null;
  /** Casa del parte; nulo si es de las obras comunes. */
  unitId: string | null;
}

export interface ExtractionSummary {
  id: string;
  developmentId: string;
  reviewStatus: ExtractionReviewStatus;
  transcriptPreview: string | null;
  proposedElements: number;
  createdAt: string;
  unitId: string | null;
  /** Estado y paso del trabajo (TRANSCRIBE, INTERPRET, AWAIT_REVIEW); nulos si no tiene. */
  jobStatus: string | null;
  pipelineStep: string | null;
  /** Por qué falló, para una persona. */
  reviewNotes: string | null;
  /** Lo subió quien mira. */
  mine: boolean;
}

export interface CreateAudioExtractionPayload {
  audioFilename: string;
  audioContentType: string;
  unitId?: string | null;
}

/** Respuesta del alta: trae la URL firmada donde subir el audio. */
export interface AudioExtractionJob {
  extractionId: string;
  jobId: string;
  reviewStatus: ExtractionReviewStatus;
  uploadUrl: string | null;
  contentType: string;
}

/**
 * Aprobación de un audio: cada entrada es un "Publicar avance" de origen audio. Las
 * alertas de insumos no viajan; el servidor conserva las que propuso el modelo.
 */
export interface ApproveExtractionPayload {
  entryDate: string;
  entries: ApprovedEntryPayload[];
  notes?: string;
}

export interface ApprovedEntryPayload {
  stageId: string | null;
  description: string;
  photoIds?: string[];
  stageUpdate?: StageUpdatePayload | null;
  /** El lugar de cada foto, por su id; la que no figura queda en "General". */
  photoSectors?: Record<string, string>;
}

export interface ApprovalResult {
  extractionId: string;
  reviewStatus: ExtractionReviewStatus;
  publishedEntries: number;
  updatedStages: number;
}

// --- Vista pública de una unidad ---

export interface PublicStage {
  name: string;
  orderIndex: number;
  status: StageStatus;
  progressPercent: number;
  description: string | null;
  plannedStart: string | null;
  plannedEnd: string | null;
  startedOn: string | null;
  completedOn: string | null;
}

/**
 * Foto o video de la obra. entryDate y stageName vienen del avance al que pertenece;
 * takenOn, de la sesión de recorrido en que se sacó.
 */
export interface PublicMedia {
  url: string;
  contentType: string;
  kind: 'PHOTO' | 'VIDEO';
  uploadedAt: string;
  entryDate: string | null;
  stageName: string | null;
  takenOn: string | null;
  /** Versiones livianas (480 y 1600 px; de un video, su fotograma); nulas mientras no estén. */
  thumbUrl: string | null;
  screenUrl: string | null;
}

/** El texto de un avance que aportó material a un lugar en un mes. */
export interface PublicPlaceNote {
  date: string;
  stageName: string | null;
  description: string;
}

/** Lo de un lugar en un mes ("2026-10"): fotos y videos en orden, si hubo recorrido 3D y los avances que aportaron. */
export interface PublicPlaceMonth {
  month: string;
  photos: PublicMedia[];
  videos: PublicMedia[];
  tour: boolean;
  notes: PublicPlaceNote[];
}

/** Un lugar de la casa con sus meses con material, del más viejo al más nuevo. "General" va al final. */
export interface PublicPlace {
  name: string;
  months: PublicPlaceMonth[];
}

export interface PublicFrontPhoto {
  url: string;
  thumbUrl: string | null;
  screenUrl: string | null;
  /** El día que muestra: el del avance, el de la sesión o el de la subida. */
  date: string;
}

/** Un render y la foto contra la que se compara, con el día que muestra esa foto. */
export interface PublicFrontProject {
  renderUrl: string;
  renderScreenUrl: string | null;
  photoUrl: string;
  /** La versión de pantalla de la foto; nula mientras no esté. */
  photoScreenUrl: string | null;
  photoDate: string;
}

/**
 * El frente de la casa. sector es el lugar marcado como frente, nulo si no hay. latest y
 * first salen sólo de fotos de sesión y de avances, nunca de la galería; first sólo viene si
 * es de otro mes que latest. renderUrl es el render del frente que cargó el dueño. project es
 * lo que compara "El proyecto": ese render contra la foto de hoy o, sin render del frente, la
 * comparación más reciente de la casa en Proyecto y realidad.
 */
export interface PublicFront {
  sector: string | null;
  latest: PublicFrontPhoto | null;
  first: PublicFrontPhoto | null;
  renderUrl: string | null;
  renderThumbUrl: string | null;
  renderScreenUrl: string | null;
  project: PublicFrontProject | null;
}

/** INITIAL_LOAD: "Carga inicial", el trabajo hecho antes de empezar el seguimiento, con fechas aproximadas. */
export type ProgressSource = 'MANUAL' | 'AUDIO' | 'INITIAL_LOAD';

export interface PublicProgressEntry {
  date: string;
  stageName: string | null;
  description: string;
  source: ProgressSource;
  corrected: boolean;
  photos: PublicMedia[];
  /** Novedad de las obras comunes del desarrollo, no de la casa. */
  common: boolean;
  /** Lo que el avance le hizo a su etapa al publicarse; nulo si no la tocó. */
  stageChange: PublicStageChange | null;
}

/** "35% → 50%", "En ejecución → Demorado". */
export interface PublicStageChange {
  percentBefore: number | null;
  percentAfter: number | null;
  statusBefore: string | null;
  statusAfter: string | null;
}

export interface PublicTransform {
  positionX: number;
  positionY: number;
  positionZ: number;
  rotationX: number;
  rotationY: number;
  rotationZ: number;
  scale: number;
}

export interface PublicCapture {
  date: string;
  description: string | null;
  zone: string | null;
  stageName: string | null;
  /** De un edificio: recorrido de áreas comunes, no de la unidad. */
  common: boolean;
  splatUrl: string;
  transform: PublicTransform;
}

/** Ficha de la unidad: cada dato es nulo si el dueño no lo cargó. */
export interface PublicUnitDetails {
  areaM2: number | null;
  bedrooms: number | null;
  bathrooms: number | null;
  features: string[];
  /** Año y mes, por ejemplo 2027-03. */
  estimatedDelivery: string | null;
  coverUrl: string | null;
  coverThumbUrl: string | null;
  coverScreenUrl: string | null;
  modelName: string | null;
  lotAreaM2: number | null;
  coveredAreaM2: number | null;
  semiCoveredAreaM2: number | null;
  rooms: number | null;
  toilettes: number | null;
  floors: number | null;
  parkingSpaces: number | null;
  orientation: string | null;
  longDescription: string | null;
  finishes: string | null;
}

/** El desarrollo visto desde la casa, sin datos comerciales. */
export interface PublicDevelopment {
  description: string | null;
  locality: string | null;
  province: string | null;
  developmentType: DevelopmentType | null;
  landAreaM2: number | null;
  amenities: string[];
  services: string[];
  architect: string | null;
  builder: string | null;
  estimatedDelivery: string | null;
  coverUrl: string | null;
  coverThumbUrl: string | null;
  coverScreenUrl: string | null;
}

/** Ambiente tocable: polygon es x1, y1, x2, y2... en metros. */
export interface PublicRoom {
  name: string;
  polygon: number[];
  areaM2: number;
  declaredArea: boolean;
}

export interface PublicFloorPlan {
  name: string;
  widthM: number;
  depthM: number;
  rooms: PublicRoom[];
  /** Plano de arquitectura, firmado. Nulo si la planta sólo tiene ambientes dibujados. */
  imageUrl: string | null;
}

export interface PublicUnitView {
  unitCode: string;
  unitDescription: string | null;
  developmentName: string;
  developmentAddress: string | null;
  developmentKind: DevelopmentKind;
  details: PublicUnitDetails;
  /** Avance ponderado de la obra, el mismo del panel, redondeado a entero. */
  overallProgressPercent: number;
  /** Nulo si la obra no tiene etapas o ya las terminó todas. */
  currentStage: PublicStage | null;
  stages: PublicStage[];
  /** Avances vigentes, del más nuevo al más viejo. */
  timeline: PublicProgressEntry[];
  /** Capturas disponibles, en orden cronológico. */
  captures: PublicCapture[];
  /** Medios de la obra, del más nuevo al más viejo. */
  gallery: PublicMedia[];
  plans: PublicFloorPlan[];
  /** Proyecto y realidad del mismo lugar. Sólo las completas: render y foto. */
  comparisons: PublicComparison[];
  /** Galería de la casa: las fotos que se cargan para mostrarla, sin avance. */
  showcase: PublicMedia[];
  development: PublicDevelopment;
  /** Sectores del recorrido 3D de la casa, en el orden en que se recorren. */
  sectors: string[];
  /** La casa lugar por lugar: los sectores con material en su orden y "General" al final. */
  places: PublicPlace[];
  /** El backend lo manda siempre; nulo sólo en vistas armadas a mano. */
  front: PublicFront | null;
}

export interface PublicComparison {
  title: string;
  renderUrl: string;
  renderThumbUrl: string | null;
  renderScreenUrl: string | null;
  photoUrl: string;
  /** Fecha del avance al que pertenece la foto; nula si es una foto suelta. */
  photoDate: string | null;
  photoStageName: string | null;
  /** En un edificio, comparación de la obra y no de la unidad. */
  common: boolean;
}

// --- Mi propiedad y enlaces ---

export interface MyUnitSummary {
  unitId: string;
  unitCode: string;
  unitDescription: string | null;
  developmentName: string;
  developmentAddress: string | null;
  developmentKind: DevelopmentKind;
}

/**
 * preview sólo viene cuando quien mira llega por la organización ("Ver como el comprador").
 * Para el comprador es nulo y el enlace público no lo tiene: lo decide el backend.
 */
export interface MyPropertyResponse {
  unitId: string;
  property: PublicUnitView;
  preview: OwnerPreview | null;
}

/** La obra de la casa, para enlazar a donde se carga lo que falta. */
export interface OwnerPreview {
  developmentId: string;
}

export interface ShareLinkSummary {
  id: string;
  label: string | null;
  active: boolean;
  expiresAt: string | null;
  revokedAt: string | null;
  lastAccessedAt: string | null;
  accessCount: number;
  createdAt: string;
  /** Quién lo creó; sólo para el dueño. El comprador no ve nombres del equipo. */
  createdByName: string | null;
  createdByYou: boolean;
}

/** El token viaja una sola vez, en la respuesta de creación. Después sólo queda su hash. */
export interface CreatedShareLink {
  id: string;
  token: string;
  label: string | null;
  expiresAt: string | null;
  createdAt: string;
}

export interface CreateShareLinkPayload {
  label?: string;
  expiresAt?: string;
}

// --- Novedades del desarrollo ---

export type ActivityKind = 'ENTRY' | 'PHOTOS' | 'CAPTURE' | 'SUPPLY';
/** El filtro por tipo: AUDIO son los avances que salieron de un audio. */
export type ActivityFilterKind = 'ENTRY' | 'AUDIO' | 'PHOTOS' | 'CAPTURE' | 'SUPPLY';

export interface ActivityPhoto {
  url: string;
  thumbUrl: string | null;
  screenUrl: string | null;
  kind: 'PHOTO' | 'VIDEO';
  sector: string | null;
}

/** Lo que el avance le hizo a su etapa. Los avances anteriores a V32 no lo tienen. */
export interface ActivityStageChange {
  percentBefore: number | null;
  percentAfter: number | null;
  statusBefore: StageStatus | null;
  statusAfter: StageStatus | null;
}

export interface ActivityEntry {
  /** La versión vigente: la que se corrige. */
  entryId: string;
  rootId: string;
  entryDate: string;
  stageName: string | null;
  source: ProgressSource;
  description: string;
  correctedByName: string | null;
  correctedAt: string | null;
  photos: ActivityPhoto[];
  stageChange: ActivityStageChange | null;
}

export interface ActivitySession {
  day: string | null;
  photos: number;
  videos: number;
  sectors: string[];
  thumbnails: ActivityPhoto[];
}

export interface ActivityCapture {
  captureId: string;
  captureDate: string;
  zone: string | null;
  state: 'READY' | 'PROCESSING' | 'FAILED';
  failureReason: string | null;
}

/**
 * Un ítem del feed. at lo ordena: la primera publicación de un avance, la última foto de una
 * sesión, el alta de un recorrido. unitCode nulo: obras comunes. unseen sólo para el dueño.
 */
export interface ActivityItem {
  kind: ActivityKind;
  id: string;
  at: string;
  unitId: string | null;
  unitCode: string | null;
  authorId: string;
  authorName: string | null;
  unseen: boolean | null;
  entry: ActivityEntry | null;
  session: ActivitySession | null;
  capture: ActivityCapture | null;
  /** Sólo para el dueño: los avisos de materiales de un audio aprobado, tal como quedaron guardados. */
  supply: ActivitySupply | null;
}

/** Los avisos de materiales de un audio aprobado ("Falta el tablero eléctrico"). No se publican. */
export interface ActivitySupply {
  extractionId: string;
  alerts: string[];
}

/** nextCursor va como before en la página siguiente; nulo si no hay más. */
export interface ActivityPage {
  items: ActivityItem[];
  nextCursor: string | null;
  seenUntil: string | null;
}

export interface PendingAudio {
  extractionId: string;
  unitId: string | null;
  unitCode: string | null;
  authorId: string;
  authorName: string | null;
  createdAt: string;
  /** Lo que propone: "2 novedades, 1 cambio de etapa". */
  progressNotes: number | null;
  stageChanges: number | null;
}

export interface UnseenCount {
  count: number;
  more: boolean;
}

export interface ActivityQuery {
  unitId?: string | null;
  authorId?: string | null;
  kind?: ActivityFilterKind | null;
  before?: string | null;
  limit?: number | null;
}

// --- Panel del desarrollista ---

/** NO_BUDGET no es "está bien": es que no hay presupuesto contra el cual comparar. */
export type TrafficLight = 'GREEN' | 'YELLOW' | 'RED' | 'NO_BUDGET';

export interface StageDashboardRow {
  stageId: string;
  name: string;
  orderIndex: number;
  status: StageStatus;
  progressPercent: number;
  weight: number;
  budgetAmount: number | null;
  spentAmount: number;
  budgetUsedPercent: number | null;
  trafficLight: TrafficLight;
}

export interface SpendingPoint {
  month: string;
  monthlyAmount: number;
  accumulated: number;
}

/** Una casa en el panel del desarrollo: su avance contra su gasto. Las obras comunes, con unitId y code nulos. */
export interface UnitDashboardRow {
  unitId: string | null;
  code: string | null;
  progressPercent: number;
  budgetAmount: number | null;
  spentAmount: number;
  budgetUsedPercent: number | null;
  trafficLight: TrafficLight;
  /** El peor semáforo entre sus etapas: rojo, amarillo, verde y sin presupuesto, en ese orden. */
  worstStageLight: TrafficLight;
  /** La etapa que enciende ese semáforo, sólo si es una alerta (amarillo o rojo). */
  worstStageName: string | null;
  /** Con alguna etapa sin presupuesto, el costo final estimado no se compara con el presupuesto. */
  stagesWithoutBudget: number;
  stageCount: number;
}

/** Una etapa para elegir al cargar un gasto o para nombrarlo. unitId nulo: obras comunes. */
export interface StageOption {
  stageId: string;
  unitId: string | null;
  name: string;
  orderIndex: number;
}

/** Etapa en alerta (amarillo o rojo). unitCode nulo: es de las obras comunes. */
export interface AlertStage {
  stageId: string;
  name: string;
  unitId: string | null;
  unitCode: string | null;
  trafficLight: TrafficLight;
  /** Para "Gastó 62% del presupuesto con 30% de avance". Nulo sin presupuesto. */
  budgetUsedPercent: number | null;
  progressPercent: number | null;
}

/**
 * Panel de una casa (unitId con valor: sus etapas y gastos) o del desarrollo (unitId
 * nulo: totales de todo, las etapas de obras comunes y una fila por casa).
 */
export interface DashboardResponse {
  developmentId: string;
  unitId: string | null;
  developmentName: string;
  overallProgressPercent: number;
  totalBudget: number;
  totalSpent: number;
  budgetUsedPercent: number | null;
  unassignedSpent: number;
  stages: StageDashboardRow[];
  spendingCurve: SpendingPoint[];
  lastExpenseOn: string | null;
  units: UnitDashboardRow[];
  /** En una casa, sus etapas en alerta; en el desarrollo, las de todas las casas y las comunes. Rojas primero. */
  alerts: AlertStage[];
  /**
   * Gasto previsto acumulado: el presupuesto de cada etapa repartido por días entre sus fechas
   * previstas. Vacío si ninguna etapa tiene fechas y presupuesto.
   */
  plannedCurve: SpendingPoint[];
  /** Etapas con presupuesto que la curva prevista deja afuera por no tener fechas, y su monto. */
  undatedStages: number;
  undatedBudget: number;
  /** En el desarrollo, las obras comunes como una fila más (unitId y code nulos); nula en una casa. */
  commonWorks: UnitDashboardRow | null;
  /** Gastos del desarrollo sin casa ni etapa: "General del desarrollo". */
  generalSpent: number;
  /** Las etapas del alcance (todas las casas y las comunes en el desarrollo), en orden de obra. */
  stageOptions: StageOption[];
}

export interface CreateExpensePayload {
  stageId?: string | null;
  incurredOn: string;
  amount: number;
  description?: string;
  unitId?: string | null;
}

/** Corrección de un gasto: los cuatro datos se mandan siempre; la casa no cambia. */
export interface UpdateExpensePayload {
  stageId: string | null;
  incurredOn: string;
  amount: number;
  description: string | null;
}

export interface ExpenseResponse {
  id: string;
  developmentId: string;
  unitId: string | null;
  stageId: string | null;
  incurredOn: string;
  amount: number;
  description: string | null;
  createdAt: string;
}

export interface UpdateStagePlanPayload {
  progressPercent?: number;
  name?: string;
  weight?: number;
  budgetAmount?: number;
  clearBudget?: boolean;
  description?: string;
  plannedStart?: string;
  plannedEnd?: string;
  clearPlannedDates?: boolean;
}
// --- Espacio de obra ---

/** Estado comercial de una unidad. Interno de la organización: el comprador no lo ve. */
export type UnitSalesStatus = 'AVAILABLE' | 'RESERVED' | 'SOLD';

export type PriceCurrency = 'ARS' | 'USD';

/** Una casa: su ficha de venta, su estado comercial y el avance de su obra. */
export interface Unit {
  id: string;
  developmentId: string;
  /** Nombre de la casa, por ejemplo "Casa 7" o "Lote 14". */
  code: string;
  description: string | null;
  areaM2: number | null;
  bedrooms: number | null;
  bathrooms: number | null;
  features: string[];
  /** Año y mes, por ejemplo 2027-03. */
  estimatedDelivery: string | null;
  hasCover: boolean;
  /** Estado comercial. Nulo para el encargado: no ve datos de venta. */
  salesStatus: UnitSalesStatus | null;
  /** El día de la reserva o la venta; nulo si está disponible, si no se cargó o para el encargado. */
  salesStatusOn: string | null;
  /** Compradores vinculados. Nulo para el encargado, y en las respuestas de edición. */
  buyerCount: number | null;
  modelName: string | null;
  lotAreaM2: number | null;
  coveredAreaM2: number | null;
  semiCoveredAreaM2: number | null;
  rooms: number | null;
  toilettes: number | null;
  floors: number | null;
  parkingSpaces: number | null;
  orientation: string | null;
  longDescription: string | null;
  finishes: string | null;
  /** Interno: nulo para el encargado, y nunca llega al comprador. */
  listPrice: number | null;
  priceCurrency: PriceCurrency | null;
  /** Nulos en las respuestas de edición, que no recalculan el avance. */
  progressPercent: number | null;
  currentStageName: string | null;
  stageCount: number | null;
  createdAt: string;
  /** Portada firmada; sólo llega en el listado de casas. */
  coverUrl: string | null;
  coverThumbUrl: string | null;
  coverScreenUrl: string | null;
}

export interface SalesSheetPayload {
  modelName: string | null;
  lotAreaM2: number | null;
  coveredAreaM2: number | null;
  semiCoveredAreaM2: number | null;
  rooms: number | null;
  toilettes: number | null;
  floors: number | null;
  parkingSpaces: number | null;
  orientation: string | null;
  longDescription: string | null;
  finishes: string | null;
  listPrice: number | null;
  priceCurrency: PriceCurrency | null;
}

export interface CreateUnitsBatchPayload {
  prefix: string;
  firstNumber: number;
  count: number;
  withStandardStages: boolean;
}

export interface UnitDetailsPayload {
  areaM2: number | null;
  bedrooms: number | null;
  bathrooms: number | null;
  features: string[];
  estimatedDelivery: string | null;
}

export interface CoverUploadResponse {
  uploadUrl: string;
  contentType: string;
}

export interface UnitMember {
  id: string;
  unitId: string;
  userId: string;
  email: string | null;
  fullName: string | null;
  createdAt: string;
}

/** initialPassword sólo viene si la cuenta se creó en esta operación, y no se repite. */
export interface AddedUnitMember extends UnitMember {
  accountCreated: boolean;
  initialPassword: string | null;
}

export interface OrganizationMember {
  id: string;
  userId: string;
  email: string | null;
  fullName: string | null;
  role: OrganizationRole;
  createdAt: string;
}

export interface AddedOrganizationMember extends OrganizationMember {
  accountCreated: boolean;
  initialPassword: string | null;
}

/** {@code initialPassword} sólo viene si la generó el servidor; una elegida no vuelve. */
export interface ResetPasswordResponse {
  initialPassword: string | null;
}

export interface ProgressEntry {
  id: string;
  developmentId: string;
  /** Casa del avance; nulo si es de las obras comunes. */
  unitId: string | null;
  stageId: string | null;
  entryDate: string;
  description: string;
  source: ProgressSource;
  version: number;
  rootId: string;
  current: boolean;
  createdAt: string;
  /** Quién publicó la primera versión; nulo en las respuestas de alta y corrección. */
  authorName: string | null;
  /** Lo que la publicación le hizo a la etapa (de la primera versión); nulo si no la tocó. */
  stageChange: ActivityStageChange | null;
}

export interface CaptureProcessing {
  captureId: string;
  unitId: string | null;
  stageId: string | null;
  captureDate: string;
  description: string | null;
  zone: string | null;
  createdAt: string;
  jobStatus: 'UPLOADED' | 'PENDING' | 'PROCESSING' | 'NEEDS_REVIEW' | 'COMPLETED' | 'FAILED';
  pipelineStep: string;
  stepStartedAt: string | null;
  failureReason: string | null;
  /** Si lo subió quien mira: el encargado sólo descarta lo suyo. */
  mine: boolean;
}

export interface MediaAsset {
  id: string;
  developmentId: string;
  progressEntryId: string | null;
  /** Avance al que pertenece aunque se haya corregido después de subir la foto. */
  progressRootId: string | null;
  /** Casa de la foto; nula si es de las obras comunes. */
  unitId: string | null;
  originalFilename: string;
  contentType: string;
  kind: 'PHOTO' | 'VIDEO';
  status: 'PENDING' | 'AVAILABLE';
  downloadUrl: string;
  /** Versiones livianas (480 y 1600 px); nulas mientras no estén: se usa el original. */
  thumbUrl: string | null;
  screenUrl: string | null;
  /** Si lo subió quien mira: el encargado sólo borra lo suyo. */
  mine: boolean;
  createdAt: string;
  /** Lugar de la casa (un sector); nulo: "General". */
  sector: string | null;
  /** De la galería de venta, no evidencia de obra. */
  showcase: boolean;
  /** Día de la sesión de recorrido en que se sacó; nulo si no viene de una sesión. */
  takenOn: string | null;
}

export interface ProgressEntryPayload {
  stageId: string | null;
  entryDate: string;
  description: string;
  /** Casa del avance; nulo para las obras comunes. Una corrección conserva la casa. */
  unitId?: string | null;
  /** Fotos ya subidas y sin confirmar: se confirman con el avance, en la misma operación. */
  photoIds?: string[];
  /** El lugar de cada foto, por id; las que no figuran quedan en "General". */
  photoSectors?: Record<string, string>;
  /** Avance y estado de la etapa, que cambian con el avance o no cambia nada. Exige etapa. */
  stageUpdate?: StageUpdatePayload | null;
}

/** Lo único que un avance cambia de su etapa. El presupuesto y el peso no viajan acá. */
export interface StageUpdatePayload {
  /** Nulo: el porcentaje no cambia. La IA propone estados, nunca porcentajes. */
  progressPercent: number | null;
  status: Extract<StageStatus, 'IN_PROGRESS' | 'DELAYED' | 'COMPLETED'>;
}

/** Subida de una foto pendiente: el porcentaje mientras sube y su id cuando terminó. */
export type PhotoUploadEvent = { kind: 'progress'; percent: number } | { kind: 'done'; mediaId: string };

export interface CaptureFromVideoPayload {
  captureDate: string;
  description: string | null;
  /** Unidad del edificio; nulo en una obra única o para áreas comunes. */
  unitId: string | null;
  stageId: string | null;
  zone: string | null;
  videoFilename: string;
  videoContentType: string;
}

/** Alta de un SPZ ya reconstruido. Nombre y tamaño van para que el servidor lo rechace antes de subir. */
export interface CaptureSpzPayload {
  captureDate: string;
  description: string | null;
  unitId: string | null;
  stageId: string | null;
  zone: string | null;
  filename: string;
  fileSize: number;
}

/** Un sector como lo ve el editor: su nombre y si es el frente de la casa. */
export interface SectorView {
  name: string;
  front: boolean;
}

/** Una fila del editor de sectores: el nombre que tenía (si ya existía) y si es el frente. */
export interface SectorChangePayload {
  name: string;
  previousName: string | null;
  front: boolean;
}

export interface CaptureUploadResponse {
  captureId: string;
  uploadUrl: string;
  contentType: string;
}

export interface CaptureUpdatePayload {
  captureDate: string;
  description: string | null;
  zone: string | null;
  stageId: string | null;
}

export interface CaptureJobResponse {
  captureId: string;
  jobId: string;
  jobStatus: string;
  uploadUrl: string | null;
  contentType: string | null;
}

// --- Resumen de obra ---

/** Pasos del desarrollo (DETAILS a BUYER) y de una casa (SHEET, GALLERY, PRICE, además de los comunes). */
export type SetupStepKey =
  | 'DETAILS' | 'UNITS' | 'STAGES' | 'SHEETS' | 'TEAM' | 'FIRST_ENTRY' | 'FIRST_CAPTURE' | 'BUYER'
  | 'SHEET' | 'GALLERY' | 'PRICE' | 'SECTORS';

export interface SetupStep {
  key: SetupStepKey;
  title: string;
  description: string;
  done: boolean;
}

export interface OverviewContent {
  stages: number;
  progressEntries: number;
  photos: number;
  /** Fotos de galería, cargadas para mostrar la casa. */
  galleryPhotos: number;
  capturesAvailable: number;
  capturesInProcess: number;
  capturesFailed: number;
  audiosToReview: number;
  lastEntryDate: string | null;
  lastCaptureDate: string | null;
}

export interface OverviewEconomic {
  totalBudget: number;
  totalSpent: number;
  budgetUsedPercent: number | null;
  stagesWithBudget: number;
}

export interface OverviewCommercial {
  units: number;
  available: number;
  reserved: number;
  sold: number;
  unitsWithBuyer: number;
  withDetails: number;
  withCover: number;
  withStages: number;
  withPrice: number;
}

/** Qué tiene el desarrollo. Lo económico, lo comercial y la puesta en marcha son nulos para el encargado. */
export interface DevelopmentOverview {
  developmentId: string;
  kind: DevelopmentKind;
  content: OverviewContent;
  economic: OverviewEconomic | null;
  commercial: OverviewCommercial | null;
  setup: SetupStep[] | null;
  /** Una tarjeta por casa, en el orden del listado de casas. */
  houses: HouseCard[];
}

/**
 * Lo que dice la tarjeta de una casa además de la casa misma: la etapa en curso principal y
 * cuántas más se trabajan, cuándo arranca, lo último que pasó y, sólo para el dueño, si está
 * sin ver, si tiene encargado y su peor alerta de gasto (nulos para el encargado).
 */
export interface HouseCard {
  unitId: string;
  currentStageName: string | null;
  otherRunningStages: number | null;
  plannedStart: string | null;
  lastActivity: ActivityItem | null;
  unseen: boolean | null;
  hasSupervisor: boolean | null;
  alertLight: TrafficLight | null;
  alertStageName: string | null;
}

/** Qué tiene una casa y qué le falta. Económico y puesta en marcha son nulos para el encargado. */
export interface UnitOverview {
  unitId: string;
  developmentId: string;
  unit: Unit;
  content: OverviewContent;
  economic: OverviewEconomic | null;
  setup: SetupStep[] | null;
}

// --- Equipo de obra ---

/**
 * Encargado de la empresa. assignedAt es nulo si todavía no está asignado. Asignado, trabaja
 * en todo el desarrollo (allHouses) o en las casas de unitIds.
 */
export interface TeamPerson {
  userId: string;
  email: string | null;
  fullName: string | null;
  assignedAt: string | null;
  allHouses: boolean;
  unitIds: string[];
}

/** Una casa del trabajo de campo: lo que el encargado necesita para decidir qué cargar hoy. */
export interface AssignedHouse {
  developmentId: string;
  developmentName: string;
  unitId: string;
  code: string;
  modelName: string | null;
  progressPercent: number | null;
  currentStageName: string | null;
  /** Avance de esa misma etapa; nulo si no hay etapa en curso. */
  currentStagePercent: number | null;
  stageCount: number | null;
  coverUrl: string | null;
  coverThumbUrl: string | null;
  /** Sectores que se graban en cada recorrido 3D, en orden. */
  sectors: string[];
  lastEntryDate: string | null;
  lastEntryText: string | null;
  lastCaptureDate: string | null;
  /** Sectores con recorrido en la última fecha grabada. */
  capturedSectors: number;
  capturesInProcess: number;
}

/** Encargados asignados a la obra y los de la empresa que se pueden sumar. */
export interface DevelopmentTeam {
  assigned: TeamPerson[];
  available: TeamPerson[];
}

// --- Planos, vistos por la organización ---

export interface PlanRoom {
  id: string;
  name: string;
  /** x1, y1, x2, y2... en metros, con origen arriba a la izquierda. */
  polygon: number[];
  areaM2: number;
  declaredArea: boolean;
}

export interface FloorPlan {
  id: string;
  unitId: string;
  name: string;
  orderIndex: number;
  widthM: number;
  depthM: number;
  rooms: PlanRoom[];
  imageUrl: string | null;
  imageWidthPx: number | null;
  imageHeightPx: number | null;
}

export interface RoomPayload {
  name: string;
  polygon: number[];
  declaredAreaM2: number | null;
}

// --- Comparador proyecto/realidad ---

export interface ProjectComparison {
  id: string;
  developmentId: string;
  /** Nulo: de toda la obra. */
  unitId: string | null;
  title: string;
  renderUrl: string | null;
  renderThumbUrl: string | null;
  renderScreenUrl: string | null;
  photoMediaId: string | null;
  photoUrl: string | null;
  /** Tiene render y foto: el comprador la ve. */
  complete: boolean;
  createdAt: string;
}

// --- Documentos autorizados ---

export type DocumentCategory = 'PLANO' | 'CONTRATO' | 'REGLAMENTO' | 'MEMORIA' | 'OTRO';

/** Documento privado: de una unidad, o de la obra si unitId es nulo. Nunca sale por el enlace público. */
export interface UnitDocument {
  id: string;
  unitId: string | null;
  title: string;
  category: DocumentCategory;
  originalFilename: string;
  contentType: string;
  available: boolean;
  /** Firmada con vigencia corta; nula si la subida no se confirmó. */
  downloadUrl: string | null;
  createdAt: string;
}
