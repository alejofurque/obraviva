import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ActivityPage, ActivityQuery, PendingAudio, UnseenCount } from './api.models';

/**
 * Novedades de un desarrollo. El feed entero, el contador, la marca de visto y los audios
 * en revisión son del dueño; el feed de una casa ({@code unitId}) lo usa también quien
 * trabaja en ella.
 */
@Injectable({ providedIn: 'root' })
export class ActivityService {

  private readonly http = inject(HttpClient);

  feed(developmentId: string, query: ActivityQuery = {}): Observable<ActivityPage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<ActivityPage>(`/api/developments/${developmentId}/activity`, { params });
  }

  unseenCount(developmentId: string): Observable<UnseenCount> {
    return this.http.get<UnseenCount>(`/api/developments/${developmentId}/activity/unseen-count`);
  }

  /** Visto hasta el ítem más nuevo que se mostró, no "ahora": lo que llegó mientras tanto sigue sin ver. */
  markSeen(developmentId: string, until: string): Observable<void> {
    return this.http.put<void>(`/api/developments/${developmentId}/activity/seen`, { until });
  }

  pendingAudios(developmentId: string): Observable<PendingAudio[]> {
    return this.http.get<PendingAudio[]>(`/api/developments/${developmentId}/activity/pending-audios`);
  }
}
