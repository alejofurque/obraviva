import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { FloorPlan, RoomPayload } from './api.models';

/** Planos de una unidad, administrados por el dueño. */
@Injectable({ providedIn: 'root' })
export class PlansService {

  private readonly http = inject(HttpClient);

  list(unitId: string): Observable<FloorPlan[]> {
    return this.http.get<FloorPlan[]>(`/api/units/${unitId}/plans`);
  }

  create(unitId: string, name: string, widthM: number, depthM: number): Observable<FloorPlan> {
    return this.http.post<FloorPlan>(`/api/units/${unitId}/plans`, { name, widthM, depthM });
  }

  update(plan: Pick<FloorPlan, 'id' | 'name' | 'widthM' | 'depthM' | 'orderIndex'>): Observable<FloorPlan> {
    const { id, name, widthM, depthM, orderIndex } = plan;
    return this.http.put<FloorPlan>(`/api/plans/${id}`, { name, widthM, depthM, orderIndex });
  }

  replaceRooms(planId: string, rooms: RoomPayload[]): Observable<FloorPlan> {
    return this.http.put<FloorPlan>(`/api/plans/${planId}/rooms`, { rooms });
  }

  remove(planId: string): Observable<void> {
    return this.http.delete<void>(`/api/plans/${planId}`);
  }

  /**
   * Sube la imagen del plano en tres pasos: pedir la URL firmada, subir directo al
   * almacenamiento con el mismo Content-Type, y confirmar. La confirmación convierte un
   * PDF a imagen, así que puede tardar unos segundos.
   */
  uploadImage(planId: string, file: File): Observable<FloorPlan> {
    const contentType = file.type || (file.name.toLowerCase().endsWith('.pdf') ? 'application/pdf' : 'application/octet-stream');
    return this.http
      .post<{ uploadUrl: string; contentType: string }>(`/api/plans/${planId}/image/upload-url`, { filename: file.name, contentType })
      .pipe(
        switchMap(upload => this.http.put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })),
        switchMap(() => this.http.post<FloorPlan>(`/api/plans/${planId}/image/confirm`, {})),
      );
  }

  removeImage(planId: string): Observable<FloorPlan> {
    return this.http.delete<FloorPlan>(`/api/plans/${planId}/image`);
  }
}
