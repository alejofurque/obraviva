import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateShareLinkPayload,
  CreatedShareLink,
  MyPropertyResponse,
  MyUnitSummary,
  ShareLinkSummary,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class MyPropertyService {

  private readonly http = inject(HttpClient);

  myUnits(): Observable<MyUnitSummary[]> {
    return this.http.get<MyUnitSummary[]>('/api/my-units');
  }

  property(unitId: string): Observable<MyPropertyResponse> {
    return this.http.get<MyPropertyResponse>(`/api/my-units/${unitId}`);
  }

  listShareLinks(unitId: string): Observable<ShareLinkSummary[]> {
    return this.http.get<ShareLinkSummary[]>(`/api/units/${unitId}/share-links`);
  }

  createShareLink(unitId: string, payload: CreateShareLinkPayload): Observable<CreatedShareLink> {
    return this.http.post<CreatedShareLink>(`/api/units/${unitId}/share-links`, payload);
  }

  revokeShareLink(linkId: string): Observable<ShareLinkSummary> {
    return this.http.post<ShareLinkSummary>(`/api/share-links/${linkId}/revoke`, {});
  }
}