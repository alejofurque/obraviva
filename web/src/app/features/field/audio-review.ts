import { ApprovedEntryPayload, ExtractionProposal, StageUpdatePayload } from '../../core/api/api.models';

type UpdateStatus = StageUpdatePayload['status'];

/**
 * Una tarjeta de la revisión de un audio: lo que se va a publicar como un avance. Junta
 * la novedad y el cambio de estado de la misma etapa. Un cambio de estado sin novedad
 * arranca con el fundamento que dio la IA como texto, que el revisor tiene que dejar
 * escrito: al comprador no le llega un cambio de estado sin explicación.
 */
export interface ReviewCard {
  key: number;
  stageId: string | null;
  description: string;
  /** El estado que propuso la IA para la etapa; nulo si sólo es una novedad. */
  proposedStatus: UpdateStatus | null;
  /** Si el revisor acepta cambiar la etapa. */
  acceptStatus: boolean;
  status: UpdateStatus | null;
  /** Lo pone la persona; nulo deja el porcentaje como está. */
  percent: number | null;
}

const UPDATE_STATUSES: UpdateStatus[] = ['IN_PROGRESS', 'DELAYED', 'COMPLETED'];

function asUpdateStatus(raw: string | null): UpdateStatus | null {
  const value = raw?.trim().toUpperCase() ?? '';
  return (UPDATE_STATUSES as string[]).includes(value) ? value as UpdateStatus : null;
}

/** Las tarjetas a partir de la propuesta: una por novedad, con el cambio de estado de su etapa si lo hay. */
export function reviewCards(proposal: ExtractionProposal | null): ReviewCard[] {
  let key = 0;
  const cards: ReviewCard[] = (proposal?.progressNotes ?? []).map(note => ({
    key: key++, stageId: note.stageId, description: note.description,
    proposedStatus: null, acceptStatus: false, status: null, percent: null,
  }));
  for (const change of proposal?.stageProposals ?? []) {
    const status = asUpdateStatus(change.proposedStatus);
    // Volver una etapa a pendiente no es algo que publique un avance.
    if (!change.stageId || !status) {
      continue;
    }
    const card = cards.find(candidate => candidate.stageId === change.stageId && candidate.proposedStatus === null);
    if (card) {
      Object.assign(card, { proposedStatus: status, acceptStatus: true, status });
    } else {
      cards.push({
        key: key++, stageId: change.stageId, description: change.rationale ?? '',
        proposedStatus: status, acceptStatus: true, status, percent: null,
      });
    }
  }
  return cards;
}

/** Lo que se manda al aprobar: cada tarjeta, un avance; el cambio de etapa sólo si se aceptó. */
export function approvalEntries(
  cards: ReviewCard[],
  photos: Record<number, string[]> = {},
  sectors: Record<number, Record<string, string>> = {},
): ApprovedEntryPayload[] {
  return cards.map(card => {
    const entry: ApprovedEntryPayload = {
      stageId: card.stageId,
      description: card.description.trim(),
      photoIds: photos[card.key] ?? [],
      stageUpdate: card.stageId && card.acceptStatus && card.status
        ? { status: card.status, progressPercent: card.status === 'COMPLETED' ? 100 : card.percent }
        : null,
    };
    // Sólo si alguna foto tiene lugar: sin él, todas quedan en "General".
    const placed = sectors[card.key] ?? {};
    return Object.keys(placed).length > 0 ? { ...entry, photoSectors: placed } : entry;
  });
}

/** El tipo de un audio elegido: muchos teléfonos no lo informan para un .opus de WhatsApp. */
export function audioContentType(file: Pick<File, 'name' | 'type'>): string {
  if (file.type) {
    return file.type.split(';')[0];
  }
  const extension = file.name.toLowerCase().split('.').pop() ?? '';
  const byExtension: Record<string, string> = {
    ogg: 'audio/ogg', opus: 'audio/ogg', oga: 'audio/ogg', m4a: 'audio/mp4', mp4: 'audio/mp4',
    mp3: 'audio/mpeg', wav: 'audio/wav', webm: 'audio/webm', aac: 'audio/aac', amr: 'audio/amr',
  };
  return byExtension[extension] ?? 'application/octet-stream';
}

/** El paso que se le muestra al encargado mientras el audio se procesa. */
export function audioStepLabel(summary: { reviewStatus: string; pipelineStep: string | null; jobStatus: string | null }): string {
  if (summary.reviewStatus === 'READY_FOR_REVIEW') return 'Listo para revisar';
  if (summary.reviewStatus === 'FAILED') return 'No se pudo procesar';
  if (summary.reviewStatus === 'APPROVED') return 'Publicado';
  if (summary.reviewStatus === 'REJECTED') return 'Descartado';
  if (summary.jobStatus === 'UPLOADED') return 'Subiendo…';
  return summary.pipelineStep === 'INTERPRET' ? 'Ordenando…' : 'Transcribiendo…';
}

/** "1:05": el tiempo de una grabación. */
export function elapsedLabel(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}
