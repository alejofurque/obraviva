import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AssignedHouse } from '../api/api.models';
import { AuthService } from '../auth/auth.service';
import { CurrentUser } from '../auth/auth.models';
import { FieldHouseStore } from '../../features/field/field-house.store';
import { aDevelopment } from '../../testing/fixtures';
import { OwnerNavStore } from './owner-nav.store';
import { SessionReset } from './session-reset';
import { UserContextService } from './user-context.service';

function house(unitId: string): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Los Aromos', unitId, code: `Casa ${unitId}`, modelName: null,
    progressPercent: 10, currentStageName: null, currentStagePercent: null, stageCount: 0,
    coverUrl: null, coverThumbUrl: null, sectors: [], lastEntryDate: null, lastEntryText: null,
    lastCaptureDate: null, capturedSectors: 0, capturesInProcess: 0,
  };
}

describe('Al salir y al entrar con otra cuenta', () => {
  let http: HttpTestingController;
  const user = signal<CurrentUser | null>({ id: 'carlos', email: 'carlos@obraviva.test', fullName: 'Carlos' });

  beforeEach(() => {
    user.set({ id: 'carlos', email: 'carlos@obraviva.test', fullName: 'Carlos' });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: user.asReadonly() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('obraviva.field.house.carlos');
    localStorage.removeItem('obraviva.field.house.martin');
  });

  it('las casas del encargado se olvidan y se piden de nuevo para el siguiente', () => {
    const store = TestBed.inject(FieldHouseStore);
    store.load().subscribe();
    http.expectOne('/api/my-work').flush([house('c1'), house('c2')]);
    store.select('c2');
    expect(localStorage.getItem('obraviva.field.house.carlos')).toBe('c2');

    TestBed.inject(SessionReset).reset();
    user.set({ id: 'martin', email: 'martin@obraviva.test', fullName: 'Martín' });

    expect(store.houses()).toEqual([]);
    expect(store.selected()).toBeNull();
    store.load().subscribe();
    http.expectOne('/api/my-work').flush([house('m1'), house('c2')]);
    // La elección de Carlos es de Carlos: Martín arranca en su primera casa.
    expect(store.selected()?.unitId).toBe('m1');
  });

  it('una respuesta pedida antes de salir no pisa la sesión nueva', () => {
    const store = TestBed.inject(FieldHouseStore);
    store.load().subscribe();
    const late = http.expectOne('/api/my-work');

    TestBed.inject(SessionReset).reset();
    late.flush([house('c1')]);

    expect(store.houses()).toEqual([]);
  });

  it('el contexto y el menú del dueño quedan vacíos', () => {
    const context = TestBed.inject(UserContextService);
    const nav = TestBed.inject(OwnerNavStore);
    context.ensureLoaded().subscribe();
    http.expectOne('/api/developments').flush([aDevelopment({ id: 'd1', role: 'OWNER' })]);
    http.expectOne('/api/my-units').flush([]);
    nav.setUnseen('d1', { count: 4, more: false });

    TestBed.inject(SessionReset).reset();

    expect(context.developments()).toEqual([]);
    expect(nav.unseen()).toEqual({});
  });
});
