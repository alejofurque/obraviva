import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateExpensePayload,
  DashboardResponse,
  ExpenseResponse,
  Stage,
  UpdateExpensePayload,
  UpdateStagePlanPayload,
} from './api.models';

@Injectable({ providedIn: 'root' })
export class DashboardService {

  private readonly http = inject(HttpClient);

  /** Con casa, el panel de esa casa; sin casa, el del desarrollo con una fila por casa. */
  dashboard(developmentId: string, unitId: string | null = null): Observable<DashboardResponse> {
    return this.http.get<DashboardResponse>(`/api/developments/${developmentId}/dashboard`,
      unitId ? { params: { unitId } } : {});
  }

  createExpense(developmentId: string, payload: CreateExpensePayload): Observable<ExpenseResponse> {
    return this.http.post<ExpenseResponse>(
      `/api/developments/${developmentId}/expenses`, payload);
  }

  expenses(developmentId: string): Observable<ExpenseResponse[]> {
    return this.http.get<ExpenseResponse[]>(`/api/developments/${developmentId}/expenses`);
  }

  /** Corrige etapa, fecha, monto y concepto. La casa del gasto no cambia. */
  updateExpense(expenseId: string, payload: UpdateExpensePayload): Observable<ExpenseResponse> {
    return this.http.patch<ExpenseResponse>(`/api/expenses/${expenseId}`, payload);
  }

  deleteExpense(expenseId: string): Observable<void> {
    return this.http.delete<void>(`/api/expenses/${expenseId}`);
  }

  /**
   * El cuerpo lleva clearBudget explícito siempre: Jackson rechaza el record si falta
   * un componente y el backend devuelve 400. Ya nos pasó.
   */
  updateStagePlan(stageId: string, payload: UpdateStagePlanPayload): Observable<Stage> {
    return this.http.patch<Stage>(`/api/stages/${stageId}/plan`, {
      clearBudget: false,
      ...payload,
    });
  }
}