import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Development, FloorPlan } from '../../core/api/api.models';
import { depthFromImage, placePoint, polygonArea, snap, toFlat, toPoints } from './plan-geometry';
import { WorkPlansComponent } from './work-plans.component';
import { WorkStore } from './work.store';
import { aDevelopment } from '../../testing/fixtures';

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ') ?? '';

describe('Geometría del editor de planos', () => {
  it('ajusta a cinco centímetros y deja cada vértice dentro de la planta', () => {
    expect(snap(3.023)).toBe(3);
    expect(snap(3.026)).toBe(3.05);
    expect(placePoint({ x: -1, y: 14.2 }, 12, 10)).toEqual({ x: 0, y: 10 });
  });

  it('calcula la superficie de un rectángulo y de una L', () => {
    expect(polygonArea(toPoints([0, 0, 4, 0, 4, 5, 0, 5]))).toBe(20);
    expect(polygonArea(toPoints([4, 0, 7, 0, 7, 2, 6, 2, 6, 3, 4, 3]))).toBe(8);
    expect(toFlat(toPoints([1, 2, 3, 4]))).toEqual([1, 2, 3, 4]);
  });

  it('saca la profundidad real del ancho y las proporciones de la imagen', () => {
    expect(depthFromImage(12, 3000, 2121)).toBe(8.48);
  });
});

describe('WorkPlansComponent', () => {
  function plan(overrides: Partial<FloorPlan> = {}): FloorPlan {
    return {
      id: 'p1', unitId: 'u1', name: 'Planta baja', orderIndex: 0, widthM: 12, depthM: 8.48, rooms: [],
      imageUrl: 'https://s/plano.png', imageWidthPx: 3000, imageHeightPx: 2121, ...overrides,
    };
  }

  function render(): { element: HTMLElement; http: HttpTestingController; component: WorkPlansComponent; detect: () => void; stable: () => Promise<unknown> } {
    const work: Development = aDevelopment({ overallProgressPercent: 50 });
    TestBed.configureTestingModule({
      imports: [WorkPlansComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ unitId: 'u1' }), queryParamMap: convertToParamMap({}) } } },
        { provide: WorkStore, useValue: { development: signal(work).asReadonly(), isOwner: computed(() => true), isSingle: computed(() => true) } },
      ],
    });
    const fixture = TestBed.createComponent(WorkPlansComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/units/u1/plans').flush([plan()]);
    http.expectOne('/api/developments/d1/units').flush([]);
    fixture.detectChanges();
    return { element: fixture.nativeElement, http, component: fixture.componentInstance, detect: () => fixture.detectChanges(), stable: () => fixture.whenStable() };
  }

  it('muestra el plano subido y guarda los ambientes marcados encima, en metros', async () => {
    const { element, http, component, detect, stable } = render();

    expect(element.querySelector('image')?.getAttribute('href')).toBe('https://s/plano.png');
    expect(text(element)).toContain('3000 × 2121 px');

    const click = (label: string) => Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => text(button).includes(label))!.click();

    click('Marcar un ambiente');
    detect();
    component.addPoint({ x: 0, y: 0 });
    component.addPoint({ x: 4, y: 0 });
    component.addPoint({ x: 4, y: 5 });
    component.addPoint({ x: 0, y: 5 });
    detect();
    click('Cerrar ambiente');
    detect();
    // ngModel escribe su valor inicial de forma asincrónica: se espera antes de tipear.
    await stable();
    expect(text(element)).toContain('20 m²');

    const name = element.querySelector<HTMLInputElement>('#room-name')!;
    name.value = 'Living';
    name.dispatchEvent(new Event('input'));
    // ngModel aplica el valor de forma asincrónica.
    await stable();
    detect();
    click('Agregar ambiente');
    detect();
    expect(text(element)).toContain('Cambios sin guardar');

    click('Guardar ambientes');
    const save = http.expectOne('/api/plans/p1/rooms');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ rooms: [{ name: 'Living', polygon: [0, 0, 4, 0, 4, 5, 0, 5], declaredAreaM2: null }] });
  });
});
