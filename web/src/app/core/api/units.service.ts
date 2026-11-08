import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import {
  AddedUnitMember,
  CoverUploadResponse,
  ResetPasswordResponse,
  SalesSheetPayload,
  SectorChangePayload,
  SectorView,
  Unit,
  UnitDetailsPayload,
  UnitMember,
  UnitSalesStatus,
} from './api.models';

/** Administración de una unidad desde su obra: ficha, portada y compradores. */
@Injectable({ providedIn: 'root' })
export class UnitsService {

  private readonly http = inject(HttpClient);

  /** Sectores del recorrido 3D, en orden. */
  sectors(unitId: string): Observable<string[]> {
    return this.http.get<string[]>(`/api/units/${unitId}/sectors`);
  }

  /** Reemplaza la lista completa. Los recorridos ya grabados no cambian. */
  replaceSectors(unitId: string, names: string[]): Observable<string[]> {
    return this.http.put<string[]>(`/api/units/${unitId}/sectors`, { names });
  }

  /** Los sectores con la marca del frente, para el editor. */
  sectorDetail(unitId: string): Observable<SectorView[]> {
    return this.http.get<SectorView[]>(`/api/units/${unitId}/sectors/detail`);
  }

  /**
   * Guarda la lista del editor fila por fila: con `previousName`, cambiar el texto es un
   * renombre que lleva fotos y recorridos al nombre nuevo; sin él, es un sector nuevo.
   * Quitar uno con material responde 409 con el conteo.
   */
  saveSectors(unitId: string, sectors: SectorChangePayload[]): Observable<string[]> {
    return this.http.put<string[]>(`/api/units/${unitId}/sectors`, { sectors });
  }

  /** Nombre y descripción corta de la casa. */
  rename(unitId: string, code: string, description: string | null): Observable<Unit> {
    return this.http.put<Unit>(`/api/units/${unitId}`, { code, description });
  }

  /** Reemplaza la ficha de venta completa, precio incluido: lo que no viene queda vacío. */
  updateSalesSheet(unitId: string, payload: SalesSheetPayload): Observable<Unit> {
    return this.http.put<Unit>(`/api/units/${unitId}/sales-sheet`, payload);
  }

  updateDetails(unitId: string, payload: UnitDetailsPayload): Observable<Unit> {
    return this.http.put<Unit>(`/api/units/${unitId}/details`, payload);
  }

  /**
   * Sube la portada en tres pasos: pedir la URL firmada, subir la imagen directo al
   * almacenamiento con el mismo Content-Type, y confirmar. El PUT va a otro origen y no
   * lleva el token: el interceptor sólo lo adjunta a la API propia.
   */
  uploadCover(unitId: string, file: File): Observable<Unit> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<CoverUploadResponse>(`/api/units/${unitId}/cover/upload-url`, { filename: file.name, contentType })
      .pipe(
        switchMap(upload => this.http.put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })),
        switchMap(() => this.http.post<Unit>(`/api/units/${unitId}/cover/confirm`, {})),
      );
  }

  /**
   * Sube el render del frente, en los mismos tres pasos que la portada. Es un dato de la
   * casa, no una foto de obra: no pasa por los medios.
   */
  uploadFrontRender(unitId: string, file: File): Observable<Unit> {
    const contentType = file.type || 'application/octet-stream';
    return this.http
      .post<CoverUploadResponse>(`/api/units/${unitId}/front-render/upload-url`, { filename: file.name, contentType })
      .pipe(
        switchMap(upload => this.http.put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })),
        switchMap(() => this.http.post<Unit>(`/api/units/${unitId}/front-render/confirm`, {})),
      );
  }

  /** {@code on}: el día de la reserva o la venta; sin él, el servidor usa el de hoy. */
  changeSalesStatus(unitId: string, status: UnitSalesStatus, on: string | null = null): Observable<Unit> {
    return this.http.put<Unit>(`/api/units/${unitId}/sales-status`, { status, on });
  }

  members(unitId: string): Observable<UnitMember[]> {
    return this.http.get<UnitMember[]>(`/api/units/${unitId}/members`);
  }

  /** Con contraseña, la cuenta nueva se crea con esa; sin ella, el servidor genera una. */
  addMember(unitId: string, email: string, fullName: string | null, password: string | null = null): Observable<AddedUnitMember> {
    return this.http.post<AddedUnitMember>(`/api/units/${unitId}/members`,
      password ? { email, fullName, password } : { email, fullName });
  }

  removeMember(memberId: string): Observable<void> {
    return this.http.delete<void>(`/api/unit-members/${memberId}`);
  }

  /** Sin contraseña, el servidor genera una y la devuelve una vez; con contraseña, no la devuelve. */
  resetMemberPassword(memberId: string, password: string | null = null): Observable<ResetPasswordResponse> {
    return this.http.post<ResetPasswordResponse>(`/api/unit-members/${memberId}/reset-password`,
      password ? { password } : {});
  }
}
