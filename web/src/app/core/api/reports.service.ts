import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApproveReportPayload,
  GenerateReportPayload,
  RejectReportPayload,
  ReportDetail,
  ReportSummary,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class ReportsService {

  private readonly http = inject(HttpClient);

  generate(developmentId: string, payload: GenerateReportPayload): Observable<ReportDetail> {
    return this.http.post<ReportDetail>(`/api/developments/${developmentId}/reports`, payload);
  }

  listByDevelopment(developmentId: string): Observable<ReportSummary[]> {
    return this.http.get<ReportSummary[]>(`/api/developments/${developmentId}/reports`);
  }

  /** Bandeja del revisor: lo redactado que espera decisión, de todas las obras. */
  listPendingReview(): Observable<ReportSummary[]> {
    return this.http.get<ReportSummary[]>('/api/reports/pending-review');
  }

  detail(reportId: string): Observable<ReportDetail> {
    return this.http.get<ReportDetail>(`/api/reports/${reportId}`);
  }

  approve(reportId: string, payload: ApproveReportPayload): Observable<ReportDetail> {
    return this.http.post<ReportDetail>(`/api/reports/${reportId}/approve`, payload);
  }

  reject(reportId: string, payload: RejectReportPayload): Observable<ReportDetail> {
    return this.http.post<ReportDetail>(`/api/reports/${reportId}/reject`, payload);
  }
}