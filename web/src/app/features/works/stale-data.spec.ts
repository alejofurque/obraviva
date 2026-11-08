import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AssignedHouse } from '../../core/api/api.models';
import { AuthService } from '../../core/auth/auth.service';
import { OwnerNavStore } from '../../core/session/owner-nav.store';
import { aDevelopment, aStage, aUnit } from '../../testing/fixtures';
import { FieldHouseStore } from '../field/field-house.store';
import { HouseStore } from './house.store';
import { HousesComponent } from './houses.component';
import { WorkAudioReviewComponent } from './work-audio-review.component';
import { WorkStore } from './work.store';

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function house(progressPercent: number): AssignedHouse {
  return {
    developmentId: 'd1', developmentName: 'Los Aromos', unitId: 'u1', code: 'Casa 1', modelName: null,
    progressPercent, currentStageName: 'Revoques', currentStagePercent: 40, stageCount: 12,
    coverUrl: null, coverThumbUrl: null, sectors: [], lastEntryDate: null, lastEntryText: null,
    lastCaptureDate: null, capturedSectors: 0, capturesInProcess: 0,
  };
}

describe('Datos viejos después de guardar', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('Hoy vuelve a pedir las casas si algo las dejó viejas, sin dejar de mostrar las que tenía', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Carlos', email: 'c@x.test' }).asReadonly() } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(FieldHouseStore);
    store.load().subscribe();
    http.expectOne('/api/my-work').flush([house(40)]);

    store.load().subscribe();
    http.expectNone('/api/my-work');

    store.invalidate();
    store.load().subscribe();
    expect(store.selected()?.progressPercent).toBe(40);
    http.expectOne('/api/my-work').flush([house(55)]);
    expect(store.selected()?.progressPercent).toBe(55);
  });

  it('aprobar un parte dentro de una casa pone al día el encabezado de la casa y deja viejo a Hoy', () => {
    const workReload = vi.fn();
    const houseReload = vi.fn();
    TestBed.configureTestingModule({
      imports: [WorkAudioReviewComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ extractionId: 'x1' }) } } },
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment({ role: 'OWNER' })).asReadonly(), isOwner: computed(() => true), reload: workReload },
        },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), unit: computed(() => aUnit()), reload: houseReload } },
      ],
    });
    const invalidate = vi.spyOn(TestBed.inject(FieldHouseStore), 'invalidate');
    const fixture = TestBed.createComponent(WorkAudioReviewComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const extraction = {
      id: 'x1', developmentId: 'd1', reviewStatus: 'READY_FOR_REVIEW', transcript: 'se terminó la losa',
      proposal: { stageProposals: [], progressNotes: [{ stageId: 's1', description: 'Se terminó la losa', confidence: 'HIGH' }], supplyAlerts: [] },
      promptVersion: 'v1', modelId: 'mock', audioUrl: null, createdAt: '2026-09-10T12:00:00Z',
      reviewedBy: null, reviewedAt: null, reviewNotes: null, unitId: 'u1',
    };
    http.expectOne('/api/audio-extractions/x1').flush(extraction);
    http.expectOne('/api/units/u1/stages').flush([aStage()]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(button => text(button).includes('Confirmar y publicar'))!.click();
    http.expectOne('/api/audio-extractions/x1/approve').flush({ publishedEntries: 1, updatedStages: 0 });
    http.expectOne('/api/audio-extractions/x1').flush({ ...extraction, reviewStatus: 'APPROVED' });
    http.expectOne('/api/units/u1/stages').flush([aStage()]);

    expect(workReload).toHaveBeenCalled();
    expect(houseReload).toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalled();
  });

  it('agregar casas las suma al menú lateral sin recargar', async () => {
    TestBed.configureTestingModule({
      imports: [HousesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment({ role: 'OWNER' })).asReadonly(), isOwner: computed(() => true), reload: () => {} },
        },
      ],
    });
    const reloadUnits = vi.spyOn(TestBed.inject(OwnerNavStore), 'reloadUnits').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(HousesComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/units').flush([aUnit({ id: 'u1', code: 'Casa 1' })]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(b => text(b).includes('Agregar casas'))!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    element.querySelector<HTMLFormElement>('.panel form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/developments/d1/units/batch').flush([aUnit({ id: 'u2', code: 'Casa 2' })]);
    http.expectOne('/api/developments/d1/units').flush([aUnit({ id: 'u1', code: 'Casa 1' }), aUnit({ id: 'u2', code: 'Casa 2' })]);

    expect(reloadUnits).toHaveBeenCalledWith('d1');
  });
});
