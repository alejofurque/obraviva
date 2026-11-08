import { HttpClient, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map } from 'rxjs';
import {
  ApprovalResult,
  ApproveExtractionPayload,
  AudioExtractionJob,
  CreateAudioExtractionPayload,
  ExtractionDetail,
  ExtractionSummary,
  RejectReportPayload,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class AudioExtractionsService {

  private readonly http = inject(HttpClient);

  /** Paso 1: registra la extracción y devuelve la URL firmada de subida. */
  create(
    developmentId: string,
    payload: CreateAudioExtractionPayload,
  ): Observable<AudioExtractionJob> {
    return this.http.post<AudioExtractionJob>(
      `/api/developments/${developmentId}/audio-extractions`, payload);
  }

  /**
   * Paso 2: sube el archivo directo al almacenamiento.
   *
   * <p>No pasa por el backend: la URL viene firmada y el archivo viaja del navegador
   * al almacenamiento sin intermediarios. El interceptor no adjunta el token a esta
   * petición porque no apunta a nuestra API.
   */
  uploadAudio(uploadUrl: string, file: File, contentType: string): Observable<void> {
    return this.http.put<void>(uploadUrl, file, {
      headers: { 'Content-Type': contentType },
    });
  }

  /** Paso 2, con el porcentaje de la subida: emite de 0 a 100 y completa. */
  uploadAudioWithProgress(uploadUrl: string, file: Blob, contentType: string): Observable<number> {
    return this.http.put(uploadUrl, file, { headers: { 'Content-Type': contentType }, reportProgress: true, observe: 'events' })
      .pipe(
        filter(event => event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response),
        map(event => event.type === HttpEventType.UploadProgress && event.total
          ? Math.round((100 * event.loaded) / event.total)
          : 100),
      );
  }

  /** Paso 3: confirma la subida, lo que encola el procesamiento. */
  confirmUpload(extractionId: string): Observable<AudioExtractionJob> {
    return this.http.post<AudioExtractionJob>(
      `/api/audio-extractions/${extractionId}/confirm-audio`, {});
  }

  detail(extractionId: string): Observable<ExtractionDetail> {
    return this.http.get<ExtractionDetail>(`/api/audio-extractions/${extractionId}`);
  }

  listByDevelopment(developmentId: string): Observable<ExtractionSummary[]> {
    return this.http.get<ExtractionSummary[]>(
      `/api/developments/${developmentId}/audio-extractions`);
  }

  listPendingReview(): Observable<ExtractionSummary[]> {
    return this.http.get<ExtractionSummary[]>('/api/audio-extractions/pending-review');
  }

  approve(extractionId: string, payload: ApproveExtractionPayload): Observable<ApprovalResult> {
    return this.http.post<ApprovalResult>(
      `/api/audio-extractions/${extractionId}/approve`, payload);
  }

  reject(extractionId: string, payload: RejectReportPayload): Observable<ExtractionDetail> {
    return this.http.post<ExtractionDetail>(
      `/api/audio-extractions/${extractionId}/reject`, payload);
  }
}