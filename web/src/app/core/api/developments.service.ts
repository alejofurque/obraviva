import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import {
  AssignedHouse,
  CaptureProcessing,
  CoverUploadResponse,
  CreateDevelopmentPayload,
  CreateUnitsBatchPayload,
  Development,
  DevelopmentOverview,
  DevelopmentTeam,
  ProgressEntry,
  Stage,
  Unit,
  UnitOverview,
  UpdateDevelopmentPayload,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class DevelopmentsService {

  private readonly http = inject(HttpClient);

  list(): Observable<Development[]> {
    return this.http.get<Development[]>('/api/developments');
  }

  detail(developmentId: string): Observable<Development> {
    return this.http.get<Development>(`/api/developments/${developmentId}`);
  }

  create(payload: CreateDevelopmentPayload): Observable<Development> {
    return this.http.post<Development>('/api/developments', payload);
  }

  /** Datos del desarrollo: lo que el comprador quiere saber del barrio. */
  update(developmentId: string, payload: UpdateDevelopmentPayload): Observable<Development> {
    return this.http.put<Development>(`/api/developments/${developmentId}`, payload);
  }

  /** Portada del desarrollo: URL firmada, subida directa al almacenamiento y confirmación. */
  uploadCover(developmentId: string, file: File): Observable<Development> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<CoverUploadResponse>(`/api/developments/${developmentId}/cover/upload-url`, { filename: file.name, contentType })
      .pipe(
        switchMap(upload => this.http.put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })),
        switchMap(() => this.http.post<Development>(`/api/developments/${developmentId}/cover/confirm`, {})),
      );
  }

  overview(developmentId: string): Observable<DevelopmentOverview> {
    return this.http.get<DevelopmentOverview>(`/api/developments/${developmentId}/overview`);
  }

  /** Encargados de la obra. Un encargado sólo ve las obras a las que está asignado. */
  team(developmentId: string): Observable<DevelopmentTeam> {
    return this.http.get<DevelopmentTeam>(`/api/developments/${developmentId}/team`);
  }

  /** Sin casas, el encargado queda en todo el desarrollo. */
  assignSupervisor(developmentId: string, userId: string, unitIds: string[] = []): Observable<DevelopmentTeam> {
    return this.http.post<DevelopmentTeam>(`/api/developments/${developmentId}/team`, { userId, unitIds });
  }

  /** En qué casas trabaja un encargado ya asignado. Vacío: todo el desarrollo. */
  changeSupervisorHouses(developmentId: string, userId: string, unitIds: string[]): Observable<DevelopmentTeam> {
    return this.http.put<DevelopmentTeam>(`/api/developments/${developmentId}/team/${userId}`, { unitIds });
  }

  /** Las casas en las que la persona carga avances y recorridos: su trabajo de campo. */
  myWork(): Observable<AssignedHouse[]> {
    return this.http.get<AssignedHouse[]>('/api/my-work');
  }

  unassignSupervisor(developmentId: string, userId: string): Observable<DevelopmentTeam> {
    return this.http.delete<DevelopmentTeam>(`/api/developments/${developmentId}/team/${userId}`);
  }

  stages(developmentId: string): Observable<Stage[]> {
    return this.http.get<Stage[]>(`/api/developments/${developmentId}/stages`);
  }

  units(developmentId: string): Observable<Unit[]> {
    return this.http.get<Unit[]>(`/api/developments/${developmentId}/units`);
  }

  createUnit(developmentId: string, code: string, description: string | null, withStandardStages = true): Observable<Unit> {
    return this.http.post<Unit>(`/api/developments/${developmentId}/units`, { code, description, withStandardStages });
  }

  /** Varias casas de una vez: "Casa 1" a "Casa 10", con las etapas estándar si se pide. */
  createUnits(developmentId: string, payload: CreateUnitsBatchPayload): Observable<Unit[]> {
    return this.http.post<Unit[]>(`/api/developments/${developmentId}/units/batch`, payload);
  }

  unitOverview(unitId: string): Observable<UnitOverview> {
    return this.http.get<UnitOverview>(`/api/units/${unitId}/overview`);
  }

  progress(developmentId: string): Observable<ProgressEntry[]> {
    return this.http.get<ProgressEntry[]>(`/api/developments/${developmentId}/progress`);
  }

  capturesInProcess(developmentId: string): Observable<CaptureProcessing[]> {
    return this.http.get<CaptureProcessing[]>(`/api/developments/${developmentId}/captures/processing`);
  }
}
