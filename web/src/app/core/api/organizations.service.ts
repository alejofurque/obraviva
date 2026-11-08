import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AddedOrganizationMember,
  Organization,
  OrganizationMember,
  ResetPasswordResponse,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class OrganizationsService {

  private readonly http = inject(HttpClient);

  list(): Observable<Organization[]> {
    return this.http.get<Organization[]>('/api/organizations');
  }

  create(name: string): Observable<Organization> {
    return this.http.post<Organization>('/api/organizations', { name });
  }

  members(organizationId: string): Observable<OrganizationMember[]> {
    return this.http.get<OrganizationMember[]>(`/api/organizations/${organizationId}/members`);
  }

  addMember(organizationId: string, email: string, fullName: string | null): Observable<AddedOrganizationMember> {
    return this.http.post<AddedOrganizationMember>(
      `/api/organizations/${organizationId}/members`, { email, fullName });
  }

  removeMember(organizationId: string, memberId: string): Observable<void> {
    return this.http.delete<void>(`/api/organizations/${organizationId}/members/${memberId}`);
  }

  resetMemberPassword(organizationId: string, memberId: string): Observable<ResetPasswordResponse> {
    return this.http.post<ResetPasswordResponse>(
      `/api/organizations/${organizationId}/members/${memberId}/reset-password`, {});
  }
}
