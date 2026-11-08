import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PublicUnitView } from './api.models';

/**
 * Vista pública de una unidad. Único servicio del frontend que funciona sin sesión:
 * la credencial es el token del enlace, que va en la dirección.
 */
@Injectable({ providedIn: 'root' })
export class PublicShareService {

  private readonly http = inject(HttpClient);

  view(token: string): Observable<PublicUnitView> {
    return this.http.get<PublicUnitView>(`/api/public/share/${encodeURIComponent(token)}`);
  }
}