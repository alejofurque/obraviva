import { HttpClient, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map, switchMap } from 'rxjs';
import {
  Capture3d,
  CaptureFromVideoPayload,
  CaptureJobResponse,
  CaptureProcessing,
  CaptureSpzPayload,
  CaptureTransformDto,
  CaptureUpdatePayload,
  CaptureUploadResponse,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class CapturesService {

  private readonly http = inject(HttpClient);

  listByDevelopment(developmentId: string): Observable<Capture3d[]> {
    return this.http.get<Capture3d[]>(`/api/developments/${developmentId}/captures`);
  }

  inProcess(developmentId: string): Observable<CaptureProcessing[]> {
    return this.http.get<CaptureProcessing[]>(`/api/developments/${developmentId}/captures/processing`);
  }

  updateTransform(captureId: string, transform: CaptureTransformDto): Observable<Capture3d> {
    return this.http.patch<Capture3d>(`/api/captures/${captureId}/transform`, { transform });
  }

  update(captureId: string, payload: CaptureUpdatePayload): Observable<Capture3d> {
    return this.http.patch<Capture3d>(`/api/captures/${captureId}`, payload);
  }

  discard(captureId: string): Observable<void> {
    return this.http.delete<void>(`/api/captures/${captureId}`);
  }

  /** Si es simulado, subir un video no consume créditos del servicio de reconstrucción. */
  reconstructionMode(): Observable<{ simulated: boolean }> {
    return this.http.get<{ simulated: boolean }>('/api/reconstruction/mode');
  }

  /** Sólo para el dueño. {@code credits} nulo en modo simulado o si el servicio no respondió. */
  reconstructionCredits(): Observable<{ simulated: boolean; credits: number | null }> {
    return this.http.get<{ simulated: boolean; credits: number | null }>('/api/reconstruction/credits');
  }

  /**
   * Alta de un SPZ ya reconstruido (exportado de Scaniverse): devuelve la URL firmada. El
   * servidor rechaza acá un archivo que no es .spz o que pasa los 50 MB, sin crear nada.
   */
  createSpz(developmentId: string, payload: CaptureSpzPayload): Observable<CaptureUploadResponse> {
    return this.http.post<CaptureUploadResponse>(`/api/developments/${developmentId}/captures`, payload);
  }

  /** Alta de un video para reconstruir: la captura, el trabajo y la URL firmada, sin encolar todavía. */
  createFromVideo(developmentId: string, payload: CaptureFromVideoPayload): Observable<CaptureJobResponse> {
    return this.http.post<CaptureJobResponse>(`/api/developments/${developmentId}/captures/from-video`, payload);
  }

  /** Sube un archivo a su URL firmada, informando el porcentaje: emite de 0 a 100 y completa. */
  putWithProgress(uploadUrl: string, file: File, contentType: string): Observable<number> {
    return this.http.put(uploadUrl, file, { headers: { 'Content-Type': contentType }, reportProgress: true, observe: 'events' })
      .pipe(
        filter(event => event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response),
        map(event => event.type === HttpEventType.UploadProgress && event.total
          ? Math.round((100 * event.loaded) / event.total)
          : 100),
      );
  }

  /** Publica el SPZ: el servidor lo mide y, si no sirve, lo borra y responde 400. */
  confirmSpz(captureId: string): Observable<Capture3d> {
    return this.http.post<Capture3d>(`/api/captures/${captureId}/confirm`, {});
  }

  /** Confirma el video subido y encola la reconstrucción. */
  confirmVideo(captureId: string): Observable<CaptureJobResponse> {
    return this.http.post<CaptureJobResponse>(`/api/captures/${captureId}/confirm-video`, {});
  }

  /**
   * Sube un video para reconstruir en 3D: registrar, subir directo al almacenamiento y
   * confirmar, que es lo que encola el procesamiento.
   */
  uploadVideo(developmentId: string, payload: CaptureFromVideoPayload, file: File): Observable<CaptureJobResponse> {
    return this.http
      .post<CaptureJobResponse>(`/api/developments/${developmentId}/captures/from-video`, payload)
      .pipe(
        switchMap(job => this.http
          .put(job.uploadUrl!, file, { headers: { 'Content-Type': job.contentType ?? payload.videoContentType } })
          .pipe(switchMap(() => this.http.post<CaptureJobResponse>(`/api/captures/${job.captureId}/confirm-video`, {})))),
      );
  }
}
