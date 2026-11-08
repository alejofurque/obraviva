import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { Unit, UnitOverview } from '../../core/api/api.models';
import { aDevelopment, aUnit } from '../../testing/fixtures';
import { houseStageLine } from './house-card.component';
import { HouseLayoutComponent } from './house-layout.component';
import { HousesComponent, batchNames, nextHouseNumber } from './houses.component';
import { parseFeatures, salesSheetOf } from './unit-sheet';
import { formatPrice, houseStepPath, yearMonthLabel } from './work-labels';
import { WorkLayoutComponent } from './work-layout.component';
import { OwnerNavStore } from '../../core/session/owner-nav.store';
import { houseBuckets, sortHouses } from './work-summary.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ') ?? '';

describe('Ficha de una casa', () => {
  it('separa por coma, limpia espacios y descarta vacíos y repetidos', () => {
    expect(parseFeatures(' Cochera, galería,, Galería , Patio ')).toEqual(['Cochera', 'galería', 'Patio']);
    expect(parseFeatures('')).toEqual([]);
  });

  it('guardar la ficha conserva el precio que la casa ya tiene', () => {
    const house = aUnit({ listPrice: 189000, priceCurrency: 'USD', rooms: 5 });
    const sheet = salesSheetOf(house, { longDescription: 'Casa luminosa' });

    expect(sheet.listPrice).toBe(189000);
    expect(sheet.priceCurrency).toBe('USD');
    expect(sheet.rooms).toBe(5);
    expect(sheet.longDescription).toBe('Casa luminosa');
  });

  it('dice dónde está la obra de la casa en palabras', () => {
    expect(houseStageLine(aUnit({ stageCount: 0 }))).toBe('Sin etapas cargadas');
    expect(houseStageLine(aUnit({ stageCount: 12, progressPercent: 0 }))).toBe('Sin empezar');
    expect(houseStageLine(aUnit({ stageCount: 12, progressPercent: 45, currentStageName: 'Cubierta y techo' }))).toBe('Cubierta y techo');
    expect(houseStageLine(aUnit({ stageCount: 12, progressPercent: 100 }))).toBe('Obra terminada');
  });

  it('muestra precio y entrega como se leen', () => {
    expect(formatPrice(189000)).toBe('US$ 189.000');
    expect(formatPrice(null)).toBeNull();
    expect(yearMonthLabel('2027-06')).toBe('junio de 2027');
  });

  it('cada paso pendiente de una casa lleva a su pestaña', () => {
    expect(houseStepPath('SHEET')).toBe('ficha');
    expect(houseStepPath('PRICE')).toBe('venta');
    expect(houseStepPath('FIRST_CAPTURE')).toBe('recorridos');
  });
});

describe('Casas del desarrollo', () => {
  it('sugiere el siguiente número y muestra los nombres que va a crear', () => {
    const houses = [aUnit({ code: 'Casa 1' }), aUnit({ code: 'Casa 6' }), aUnit({ code: 'Lote A' })];

    expect(nextHouseNumber(houses, 'Casa')).toBe(7);
    expect(nextHouseNumber(houses, 'Lote')).toBe(1);
    expect(batchNames('Casa', 7, 3)).toEqual(['Casa 7', 'Casa 8', 'Casa 9']);
  });

  it('ordena Casa 2 antes que Casa 10 y cuenta cada casa según su obra', () => {
    expect(sortHouses([aUnit({ code: 'Casa 10' }), aUnit({ code: 'Casa 2' })]).map(house => house.code)).toEqual(['Casa 2', 'Casa 10']);
    expect(houseBuckets([
      aUnit({ stageCount: 12, progressPercent: 0 }),
      aUnit({ stageCount: 12, progressPercent: 40 }),
      aUnit({ stageCount: 12, progressPercent: 100 }),
      aUnit({ stageCount: 0, progressPercent: 0 }),
    ])).toEqual({ notStarted: 2, inProgress: 1, finished: 1 });
  });
});

describe('WorkLayoutComponent', () => {
  let http: HttpTestingController;

  function renderAs(role: 'OWNER' | 'MEMBER', unseen = { count: 0, more: false }): HTMLElement {
    TestBed.configureTestingModule({
      imports: [WorkLayoutComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ developmentId: 'd1' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(WorkLayoutComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1').flush(aDevelopment({
      role, overallProgressPercent: 29, unitCount: 6, completedUnitCount: 0,
      developmentType: 'BARRIO_CERRADO', locality: 'Villa Allende', province: 'Córdoba',
    }));
    if (role === 'OWNER') {
      http.expectOne('/api/developments/d1/activity/unseen-count').flush(unseen);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('muestra el barrio con sus casas y al dueño le da novedades, económico y equipo; los datos se editan desde el encabezado', () => {
    const element = renderAs('OWNER');
    const tabs = Array.from(element.querySelectorAll('nav a')).map(text);

    expect(element.querySelector('h1')).not.toBeNull();
    // El avance y las casas están en los indicadores del Resumen: el encabezado no los repite.
    expect(text(element.querySelector('app-page-header')!)).not.toContain('29%');
    expect(text(element.querySelector('.context')!)).toContain('Barrio cerrado · Villa Allende, Córdoba');
    // Novedades va entre Resumen y Casas.
    expect(tabs.slice(0, 3)).toEqual(['Resumen', 'Novedades', 'Casas']);
    for (const tab of ['Obras comunes', 'Económico', 'Equipo']) {
      expect(tabs).toContain(tab);
    }
    expect(tabs).not.toContain('Datos del desarrollo');
    const actions = Array.from(element.querySelectorAll<HTMLAnchorElement>('app-page-header .actions a'));
    expect(actions.map(link => [text(link), link.getAttribute('href')])).toEqual([
      ['Editar datos', '/obras/d1/datos'],
      ['Agregar casas', '/obras/d1/casas?nueva=1'],
    ]);
  });

  it('en Casas, "Agregar casas" es de la página: el encabezado no repite el botón', () => {
    TestBed.overrideProvider(OwnerNavStore, { useValue: { active: () => false, inHouses: () => true, setUnseen: () => undefined } });
    const element = renderAs('OWNER');
    const actions = Array.from(element.querySelectorAll('app-page-header .actions a')).map(text);
    expect(actions).toEqual(['Editar datos']);
  });

  it('en el marco del dueño las secciones están en el menú lateral: sin pestañas', () => {
    TestBed.overrideProvider(OwnerNavStore, { useValue: { active: () => true, inHouses: () => false, setUnseen: () => undefined } });
    const element = renderAs('OWNER');
    expect(element.querySelector('nav.tabs')).toBeNull();
    expect(text(element.querySelector('h1')!)).not.toBe('');
  });

  it('lo sin ver va junto a Novedades: "Novedades ●3", y con más de lo contado, "●99+"', () => {
    const tab = (element: HTMLElement) => Array.from(element.querySelectorAll('nav a')).map(text).find(label => label.startsWith('Novedades'));

    expect(tab(renderAs('OWNER', { count: 3, more: false }))).toBe('Novedades ●3');
    TestBed.resetTestingModule();
    expect(tab(renderAs('OWNER', { count: 99, more: true }))).toBe('Novedades ●99+');
  });

  it('al encargado le deja casas y obras comunes, sin novedades, datos, gasto ni equipo', () => {
    const nav = text(renderAs('MEMBER').querySelector('nav')!);

    expect(nav).toContain('Casas');
    expect(nav).toContain('Obras comunes');
    expect(nav).not.toContain('Novedades');
    expect(nav).not.toContain('Datos');
    expect(nav).not.toContain('Económico');
    expect(nav).not.toContain('Equipo');
  });
});

describe('WorkLayoutComponent para un encargado de casas puntuales', () => {
  it('ve sus casas y no las obras comunes, ni datos, gasto ni equipo', () => {
    TestBed.configureTestingModule({
      imports: [WorkLayoutComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ developmentId: 'd1' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(WorkLayoutComponent);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/developments/d1').flush(
      aDevelopment({ role: 'MEMBER', unitCount: 2, restrictedToUnitIds: ['c1', 'c2'] }));
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const nav = text(element.querySelector('nav')!);

    expect(nav).toContain('Casas');
    expect(nav).not.toContain('Obras comunes');
    expect(nav).not.toContain('Datos');
    expect(nav).not.toContain('Económico');
    expect(nav).not.toContain('Equipo');
    expect(text(element)).toContain('Sos el encargado');
  });
});

describe('HouseLayoutComponent', () => {
  function overview(unit: Unit): UnitOverview {
    return {
      unitId: unit.id, developmentId: 'd1', unit,
      content: { stages: 12, progressEntries: 3, photos: 2, galleryPhotos: 1, capturesAvailable: 1, capturesInProcess: 0, capturesFailed: 0, audiosToReview: 0, lastEntryDate: null, lastCaptureDate: null },
      economic: null, setup: null,
    };
  }

  function renderAs(role: 'OWNER' | 'MEMBER'): HTMLElement {
    TestBed.configureTestingModule({
      imports: [HouseLayoutComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ developmentId: 'd1', unitId: 'u1' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(HouseLayoutComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1').flush(aDevelopment({ role, name: 'Barrio Los Aromos' }));
    http.expectOne('/api/units/u1/overview').flush(overview(aUnit({
      code: 'Casa 1', modelName: 'Modelo Aromo', progressPercent: 68, stageCount: 12,
      currentStageName: 'Revoques, contrapisos y carpetas', salesStatus: 'SOLD',
    })));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('la casa es una obra: su avance, su etapa y, para el dueño, ficha, venta y gasto', () => {
    const element = renderAs('OWNER');
    const nav = text(element.querySelector('nav.tabs')!);

    expect(text(element.querySelector('h1')!)).toBe('Casa 1');
    expect(text(element.querySelector('.context')!)).toBe('Barrio Los Aromos · Modelo Aromo');
    expect(text(element)).toContain('En obra');
    expect(element.querySelector('.percent')?.textContent?.trim()).toBe('68%');
    expect(text(element)).toContain('Revoques, contrapisos y carpetas');
    expect(text(element)).toContain('Vendida');
    for (const tab of ['Resumen', 'Etapas', 'Historia', 'Lugares y 3D', 'Novedades', 'Ficha y galería', 'Venta', 'Económico']) {
      expect(nav).toContain(tab);
    }
    const buyer = Array.from(element.querySelectorAll<HTMLAnchorElement>('.head a')).find(link => text(link).trim() === 'Ver como el comprador');
    expect(buyer?.getAttribute('href')).toBe(`/mi-propiedad/${aUnit().id}?obra=d1`);
  });

  it('el encargado ve lo que carga, sin ficha, venta ni estado comercial', () => {
    const element = renderAs('MEMBER');
    const nav = text(element.querySelector('nav.tabs')!);

    expect(nav).toContain('Etapas');
    expect(nav).toContain('Lugares y 3D');
    expect(nav).toContain('Novedades');
    expect(nav).not.toContain('Venta');
    expect(nav).not.toContain('Ficha');
    expect(nav).not.toContain('Económico');
    expect(text(element)).not.toContain('Vendida');
    expect(text(element)).not.toContain('Ver como el comprador');
  });
});

describe('HousesComponent', () => {
  function render(role: 'OWNER' | 'MEMBER', houses: Unit[]): { element: HTMLElement; http: HttpTestingController; detect: () => void; stable: () => Promise<unknown> } {
    TestBed.configureTestingModule({
      imports: [HousesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        {
          provide: WorkStore,
          useValue: { development: signal(aDevelopment({ role })).asReadonly(), isOwner: computed(() => role === 'OWNER'), reload: () => {} },
        },
      ],
    });
    const fixture = TestBed.createComponent(HousesComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/units').flush(houses);
    fixture.detectChanges();
    return { element: fixture.nativeElement as HTMLElement, http, detect: () => fixture.detectChanges(), stable: () => fixture.whenStable() };
  }

  it('el dueño carga varias casas de una vez, con las etapas estándar', async () => {
    const { element, http, detect, stable } = render('OWNER', [aUnit({ id: 'u1', code: 'Casa 1' }), aUnit({ id: 'u2', code: 'Casa 2' })]);

    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(b => text(b).includes('Agregar casas'))!.click();
    detect();
    await stable();
    detect();
    expect(text(element.querySelector('.preview')!)).toContain('Casa 3 a Casa 12');

    element.querySelector<HTMLFormElement>('.panel form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/developments/d1/units/batch');
    expect(request.request.body).toEqual({ prefix: 'Casa', firstNumber: 3, count: 10, withStandardStages: true });
  });

  it('el filtro queda en la dirección sin sumar historial, así entrar a una casa y volver lo conserva', () => {
    const { element, detect } = render('OWNER', [aUnit({ id: 'u1', code: 'Casa 1', salesStatus: 'SOLD' })]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    Array.from(element.querySelectorAll<HTMLButtonElement>('.filter')).find(b => text(b).includes('Vendida'))!.click();
    detect();

    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { estado: 'SOLD' }, queryParamsHandling: 'merge', replaceUrl: true }));
  });

  it('el dueño filtra por venta; el encargado no ve estado, precio ni alta', () => {
    const houses = [
      aUnit({ id: 'u1', code: 'Casa 1', salesStatus: 'SOLD', buyerCount: 1, listPrice: 189000, priceCurrency: 'USD' }),
      aUnit({ id: 'u2', code: 'Casa 2', salesStatus: 'AVAILABLE' }),
    ];
    const { element, detect } = render('OWNER', houses);
    expect(element.querySelectorAll('app-house-card')).toHaveLength(2);
    expect(text(element)).toContain('US$ 189.000');

    Array.from(element.querySelectorAll<HTMLButtonElement>('.filter')).find(b => text(b).includes('Vendida'))!.click();
    detect();
    expect(element.querySelectorAll('app-house-card')).toHaveLength(1);

    TestBed.resetTestingModule();
    const member = render('MEMBER', houses).element;
    expect(text(member)).not.toContain('Agregar casas');
    expect(text(member)).not.toContain('Vendida');
    expect(member.querySelector('.filters')).toBeNull();
  });
});
