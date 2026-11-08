import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Stage, StageStatus, UpdateStagePlanPayload } from './api.models';

@Injectable({ providedIn: 'root' })
export class StagesService {

  private readonly http = inject(HttpClient);

  /** Etapas de las obras comunes del desarrollo. Las de una casa se piden por la casa. */
  listByDevelopment(developmentId: string): Observable<Stage[]> {
    return this.http.get<Stage[]>(`/api/developments/${developmentId}/stages`);
  }

  create(developmentId: string, name: string, orderIndex: number): Observable<Stage> {
    return this.http.post<Stage>(`/api/developments/${developmentId}/stages`, { name, orderIndex });
  }

  listByUnit(unitId: string): Observable<Stage[]> {
    return this.http.get<Stage[]>(`/api/units/${unitId}/stages`);
  }

  createForUnit(unitId: string, name: string, orderIndex: number): Observable<Stage> {
    return this.http.post<Stage>(`/api/units/${unitId}/stages`, { name, orderIndex });
  }

  /** Las doce etapas estándar de una casa. Sólo sobre una casa sin etapas. */
  applyStandard(unitId: string): Observable<Stage[]> {
    return this.http.post<Stage[]>(`/api/units/${unitId}/stages/standard`, {});
  }

  /**
   * Sólo se aplica lo que viene. Nombre, peso, presupuesto, descripción y fechas previstas
   * son del dueño; el avance, de ambos. Para borrar se manda clearBudget o
   * clearPlannedDates: un nulo no borra.
   */
  updatePlan(stageId: string, changes: UpdateStagePlanPayload): Observable<Stage> {
    return this.http.patch<Stage>(`/api/stages/${stageId}/plan`, changes);
  }

  /**
   * Completar una etapa lleva su avance a 100 en el servidor. {@code effectiveOn} es el día
   * en que pasó; sin fecha, hoy.
   */
  updateStatus(stageId: string, status: StageStatus, effectiveOn?: string): Observable<Stage> {
    return this.http.patch<Stage>(`/api/developments/stages/${stageId}`, { status, effectiveOn: effectiveOn ?? null });
  }
}
