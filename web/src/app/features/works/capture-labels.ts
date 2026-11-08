import { CaptureProcessing } from '../../core/api/api.models';

const STEP_LABELS: Record<string, string> = {
  PREPROCESS: 'Preparando el video',
  SUBMIT: 'Enviando a reconstrucción',
  AWAIT_EXTERNAL: 'Reconstruyendo en 3D, puede tardar hasta 20 minutos',
  DOWNLOAD: 'Descargando el resultado',
  CONVERT: 'Optimizando para el visor',
};

/** Qué mostrar de un recorrido en proceso, sin términos internos del pipeline. */
export function processingLabel(capture: Pick<CaptureProcessing, 'jobStatus' | 'pipelineStep'>): string {
  if (capture.jobStatus === 'FAILED') {
    return 'No se pudo procesar';
  }
  if (capture.jobStatus === 'UPLOADED') {
    return 'La subida del video no terminó';
  }
  return STEP_LABELS[capture.pipelineStep] ?? 'En proceso';
}

/** Un recorrido se puede descartar si no avanza más: falló o su subida quedó abandonada. */
export function isDiscardable(capture: Pick<CaptureProcessing, 'jobStatus'>): boolean {
  return capture.jobStatus === 'FAILED' || capture.jobStatus === 'UPLOADED';
}
