import { DocumentCategory } from '../../core/api/api.models';

export const DOCUMENT_CATEGORY_LABELS: Record<DocumentCategory, string> = {
  CONTRATO: 'Boleto o contrato',
  PLANO: 'Plano aprobado',
  REGLAMENTO: 'Reglamento',
  MEMORIA: 'Memoria descriptiva',
  OTRO: 'Otro',
};

export const DOCUMENT_CATEGORIES: DocumentCategory[] = ['CONTRATO', 'PLANO', 'REGLAMENTO', 'MEMORIA', 'OTRO'];
