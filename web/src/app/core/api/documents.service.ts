import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { DocumentCategory, UnitDocument } from './api.models';

/** Documentos autorizados: los administra el dueño y los ve el comprador en Mi propiedad. */
@Injectable({ providedIn: 'root' })
export class DocumentsService {

  private readonly http = inject(HttpClient);

  listForDevelopment(developmentId: string): Observable<UnitDocument[]> {
    return this.http.get<UnitDocument[]>(`/api/developments/${developmentId}/documents`);
  }

  listForUnit(unitId: string): Observable<UnitDocument[]> {
    return this.http.get<UnitDocument[]>(`/api/my-units/${unitId}/documents`);
  }

  /** Registrar, subir directo al almacenamiento y confirmar. */
  upload(
    developmentId: string,
    document: { title: string; category: DocumentCategory; unitId: string | null },
    file: File,
  ): Observable<UnitDocument> {
    return this.http
      .post<{ documentId: string; uploadUrl: string; contentType: string }>(
        `/api/developments/${developmentId}/documents/upload-url`,
        { ...document, filename: file.name, contentType: file.type || 'application/octet-stream' })
      .pipe(
        switchMap(upload => this.http
          .put(upload.uploadUrl, file, { headers: { 'Content-Type': upload.contentType } })
          .pipe(switchMap(() => this.http.post<UnitDocument>(`/api/documents/${upload.documentId}/confirm`, {})))),
      );
  }

  remove(documentId: string): Observable<void> {
    return this.http.delete<void>(`/api/documents/${documentId}`);
  }
}
