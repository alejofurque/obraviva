import { HttpClient, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map, of, switchMap } from 'rxjs';
import { MediaAsset, PhotoUploadEvent, ProgressEntry, ProgressEntryPayload } from './api.models';

/** Avances de obra y sus fotos. */
@Injectable({ providedIn: 'root' })
export class ProgressService {

  private readonly http = inject(HttpClient);

  list(developmentId: string): Observable<ProgressEntry[]> {
    return this.http.get<ProgressEntry[]>(`/api/developments/${developmentId}/progress`);
  }

  create(developmentId: string, payload: ProgressEntryPayload): Observable<ProgressEntry> {
    return this.http.post<ProgressEntry>(`/api/developments/${developmentId}/progress`, payload);
  }

  /** Una corrección no pisa: crea una versión nueva y la anterior queda en el historial. */
  correct(entryId: string, payload: ProgressEntryPayload): Observable<ProgressEntry> {
    return this.http.post<ProgressEntry>(`/api/progress/${entryId}/corrections`, payload);
  }

  media(developmentId: string): Observable<MediaAsset[]> {
    return this.http.get<MediaAsset[]>(`/api/developments/${developmentId}/media`);
  }

  /** Sube una foto a un avance: pedir URL firmada, subir directo al almacenamiento y confirmar. */
  uploadPhoto(developmentId: string, entryId: string, file: File, sector: string | null = null): Observable<MediaAsset> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<{ mediaId: string; uploadUrl: string; contentType: string }>(
        `/api/developments/${developmentId}/media/upload-url`,
        { filename: file.name, contentType, progressEntryId: entryId, sector })
      .pipe(
        switchMap(upload => this.http
          .put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })
          .pipe(switchMap(() => this.http.post<MediaAsset>(`/api/media/${upload.mediaId}/confirm`, {})))),
      );
  }

  /**
   * Sube una foto para un avance que todavía no existe. Queda pendiente, invisible para
   * todos, hasta que el avance se publica con su id en `photoIds`. No se confirma acá: una
   * foto sin avance confirmada sería una foto de la galería de la casa.
   */
  uploadPendingPhoto(developmentId: string, unitId: string, file: File): Observable<PhotoUploadEvent> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<{ mediaId: string; uploadUrl: string; contentType: string }>(
        `/api/developments/${developmentId}/media/upload-url`,
        { filename: file.name, contentType, unitId })
      .pipe(
        switchMap(upload => this.http
          .put(upload.uploadUrl, file, {
            headers: { 'Content-Type': upload.contentType }, reportProgress: true, observe: 'events',
          })
          .pipe(
            filter(event => event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response),
            map((event): PhotoUploadEvent => event.type === HttpEventType.UploadProgress
              ? { kind: 'progress', percent: event.total ? Math.round((100 * event.loaded) / event.total) : 0 }
              : { kind: 'done', mediaId: upload.mediaId }),
          )),
      );
  }

  /**
   * Foto o video de un lugar de la casa, subido en la sesión de recorrido: no es de un
   * avance ni de la galería de venta. Lleva su sector y el día de la sesión, y se publica
   * apenas sube. Emite el porcentaje y, al final, su id ya confirmado.
   */
  uploadSessionMedia(
    developmentId: string, place: { unitId: string; sector: string; takenOn: string }, file: File,
  ): Observable<PhotoUploadEvent> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<{ mediaId: string; uploadUrl: string; contentType: string }>(
        `/api/developments/${developmentId}/media/upload-url`,
        { filename: file.name, contentType, unitId: place.unitId, sector: place.sector, showcase: false, takenOn: place.takenOn })
      .pipe(
        switchMap(upload => this.http
          .put(upload.uploadUrl, file, {
            headers: { 'Content-Type': upload.contentType }, reportProgress: true, observe: 'events',
          })
          .pipe(
            filter(event => event.type === HttpEventType.UploadProgress || event.type === HttpEventType.Response),
            switchMap(event => event.type === HttpEventType.UploadProgress
              ? of<PhotoUploadEvent>({ kind: 'progress', percent: event.total ? Math.round((100 * event.loaded) / event.total) : 0 })
              : this.http.post<MediaAsset>(`/api/media/${upload.mediaId}/confirm`, {})
                .pipe(map((): PhotoUploadEvent => ({ kind: 'done', mediaId: upload.mediaId })))),
          )),
      );
  }

  /** Foto de la galería de una casa: sin avance, para mostrarla. */
  uploadGalleryPhoto(developmentId: string, unitId: string, file: File, sector: string | null = null): Observable<MediaAsset> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<{ mediaId: string; uploadUrl: string; contentType: string }>(
        `/api/developments/${developmentId}/media/upload-url`,
        { filename: file.name, contentType, unitId, showcase: true, sector })
      .pipe(
        switchMap(upload => this.http
          .put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })
          .pipe(switchMap(() => this.http.post<MediaAsset>(`/api/media/${upload.mediaId}/confirm`, {})))),
      );
  }

  /** Cambia el lugar de una foto ya publicada; nulo la pasa a "General". */
  updateMediaSector(mediaId: string, sector: string | null): Observable<MediaAsset> {
    return this.http.patch<MediaAsset>(`/api/media/${mediaId}/sector`, { sector });
  }

  /** "Mover a fotos de obra": una imagen de la galería pasa a ser foto de un lugar, con el día en que se sacó. */
  moveToWork(mediaId: string, sector: string, takenOn: string): Observable<MediaAsset> {
    return this.http.post<MediaAsset>(`/api/media/${mediaId}/to-work`, { sector, takenOn });
  }

  deleteMedia(mediaId: string): Observable<void> {
    return this.http.delete<void>(`/api/media/${mediaId}`);
  }
}
