import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { ProjectComparison } from './api.models';

/** Comparador proyecto/realidad, administrado por el dueño. */
@Injectable({ providedIn: 'root' })
export class ComparisonsService {

  private readonly http = inject(HttpClient);

  list(developmentId: string): Observable<ProjectComparison[]> {
    return this.http.get<ProjectComparison[]>(`/api/developments/${developmentId}/comparisons`);
  }

  create(developmentId: string, title: string, unitId: string | null): Observable<ProjectComparison> {
    return this.http.post<ProjectComparison>(`/api/developments/${developmentId}/comparisons`, { title, unitId });
  }

  /** Reemplaza título, alcance y foto: se mandan los tres siempre. */
  update(comparison: Pick<ProjectComparison, 'id' | 'title' | 'unitId' | 'photoMediaId'>): Observable<ProjectComparison> {
    const { id, title, unitId, photoMediaId } = comparison;
    return this.http.put<ProjectComparison>(`/api/comparisons/${id}`, { title, unitId, photoMediaId });
  }

  uploadRender(comparisonId: string, file: File): Observable<ProjectComparison> {
    return this.http
      .post<{ uploadUrl: string; contentType: string }>(`/api/comparisons/${comparisonId}/render/upload-url`, {
        filename: file.name,
        contentType: file.type || 'application/octet-stream',
      })
      .pipe(
        switchMap(upload => this.http.put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })),
        switchMap(() => this.http.post<ProjectComparison>(`/api/comparisons/${comparisonId}/render/confirm`, {})),
      );
  }

  remove(comparisonId: string): Observable<void> {
    return this.http.delete<void>(`/api/comparisons/${comparisonId}`);
  }
}
