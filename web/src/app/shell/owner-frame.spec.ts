import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { Development, Unit, UnseenCount } from '../core/api/api.models';
import { AuthService } from '../core/auth/auth.service';
import { OwnerNavStore, openPlace } from '../core/session/owner-nav.store';
import { UserContextService } from '../core/session/user-context.service';
import { MENU_HOUSES, developmentSections, houseTabs, unseenBadge } from '../features/works/work-sections';
import { aDevelopment, aUnit } from '../testing/fixtures';
import { OwnerSidebarComponent } from './owner-sidebar.component';
import { ShellComponent } from './shell.component';

@Component({ template: '' })
class BlankComponent {}

describe('Menú del dueño en el teléfono', () => {
  it('se cierra en cualquier navegación, también con el atrás del navegador', async () => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([{ path: 'a', component: BlankComponent }, { path: 'b', component: BlankComponent }]),
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Raúl Díaz', email: 'raul@obraviva.test' }).asReadonly() } },
        {
          provide: UserContextService,
          useValue: { ensureLoaded: () => of(undefined), supervises: () => false, units: () => [], managesDevelopments: () => true, homeUrl: () => ['/desarrollos'] },
        },
        {
          provide: OwnerNavStore,
          useValue: {
            active: computed(() => true), developments: signal([]).asReadonly(), owns: computed(() => true), openId: computed(() => null),
            inHouses: computed(() => false), units: computed(() => []), unseen: computed(() => ({})),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    const close = vi.spyOn(fixture.componentInstance as unknown as { closeDrawer: () => void }, 'closeDrawer');

    await TestBed.inject(Router).navigateByUrl('/a');
    expect(close).toHaveBeenCalledTimes(1);
    await TestBed.inject(Router).navigateByUrl('/b');
    expect(close).toHaveBeenCalledTimes(2);
  });
});

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Secciones del área del dueño', () => {
  it('el dueño ve Resumen, Novedades con lo sin ver, Casas, Obras comunes, Económico y Equipo; sin Datos', () => {
    const sections = developmentSections(aDevelopment({ role: 'OWNER' }), { count: 3, more: false });
    expect(sections.map(section => section.label)).toEqual(['Resumen', 'Novedades', 'Casas', 'Obras comunes', 'Económico', 'Equipo']);
    expect(sections[1].badge).toBe('3');
  });

  it('el encargado de casas puntuales ve Resumen y Casas', () => {
    const sections = developmentSections(aDevelopment({ role: 'MEMBER', restrictedToUnitIds: ['c1'] }), null);
    expect(sections.map(section => section.label)).toEqual(['Resumen', 'Casas']);
  });

  it('lo sin ver: nada con cero, "99+" con más de lo contado', () => {
    expect(unseenBadge({ count: 0, more: false })).toBeNull();
    expect(unseenBadge({ count: 99, more: true })).toBe('99+');
    expect(unseenBadge(null)).toBeNull();
  });

  it('la casa suma Novedades y llama "Lugares y 3D" a sus recorridos; lo comercial, sólo el dueño', () => {
    expect(houseTabs(false).map(tab => tab.label)).toEqual(['Resumen', 'Etapas', 'Historia', 'Lugares y 3D', 'Novedades']);
    expect(houseTabs(true).map(tab => tab.label).slice(5)).toEqual(['Ficha y galería', 'Venta', 'Económico']);
  });

  it('la dirección dice qué desarrollo está abierto y si se está en sus casas', () => {
    expect(openPlace('/obras/d1')).toEqual({ developmentId: 'd1', inHouses: false });
    expect(openPlace('/obras/d1/casas')).toEqual({ developmentId: 'd1', inHouses: true });
    expect(openPlace('/obras/d1/casas/u1/historia?corregir=e1')).toEqual({ developmentId: 'd1', inHouses: true });
    expect(openPlace('/obras/d1/novedades')).toEqual({ developmentId: 'd1', inHouses: false });
    expect(openPlace('/desarrollos')).toEqual({ developmentId: null, inHouses: false });
  });
});

describe('Marco del dueño', () => {
  function store(developments: Development[]): OwnerNavStore {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: UserContextService, useValue: { developments: signal(developments).asReadonly(), homeUrl: () => ['/desarrollos'] } },
      ],
    });
    return TestBed.inject(OwnerNavStore);
  }

  it('lo usa quien es dueño de alguna obra; quien sólo es encargado sigue con su marco', () => {
    expect(store([aDevelopment({ role: 'OWNER' })]).active()).toBe(true);
    TestBed.resetTestingModule();
    expect(store([aDevelopment({ role: 'MEMBER' })]).active()).toBe(false);
  });

  it('pide lo sin ver de cada desarrollo del que es dueño, no de los que sólo encarga', () => {
    store([aDevelopment({ id: 'd1', role: 'OWNER' }), aDevelopment({ id: 'd2', role: 'MEMBER' })]);
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/developments/d1/activity/unseen-count').flush({ count: 2, more: false });
    http.expectNone('/api/developments/d2/activity/unseen-count');
    expect(TestBed.inject(OwnerNavStore).unseen()['d1']).toEqual({ count: 2, more: false });
    http.verify();
  });
});

describe('Menú lateral del dueño', () => {
  function render(options: { units?: Unit[]; inHouses?: boolean; unseen?: Record<string, UnseenCount> } = {}): HTMLElement {
    const developments = [
      aDevelopment({ id: 'd1', name: 'Altos del Valle', role: 'OWNER' }),
      aDevelopment({ id: 'd2', name: 'Barrio Los Aromos', role: 'OWNER' }),
    ];
    TestBed.configureTestingModule({
      imports: [OwnerSidebarComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: signal({ id: 'me', fullName: 'Raúl Díaz', email: 'raul@obraviva.test' }).asReadonly() } },
        { provide: UserContextService, useValue: { supervises: () => false, units: () => [], homeUrl: () => ['/desarrollos'] } },
        {
          provide: OwnerNavStore,
          useValue: {
            developments: signal(developments).asReadonly(),
            owns: computed(() => true),
            openId: computed(() => 'd1'),
            inHouses: computed(() => options.inHouses ?? false),
            units: computed(() => options.units ?? []),
            unseen: computed(() => options.unseen ?? {}),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(OwnerSidebarComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('Inicio, los desarrollos y, en el abierto, sus secciones; los otros con su número sin ver; abajo la persona', () => {
    const element = render({ unseen: { d1: { count: 3, more: false }, d2: { count: 5, more: false } } });

    expect(text(element.querySelector('.nav > .item')!)).toBe('Inicio');
    const developments = Array.from(element.querySelectorAll('.developments > li'));
    expect(developments.map(item => text(item.querySelector('.name')!))).toEqual(['Altos del Valle', 'Barrio Los Aromos']);
    const sections = Array.from(developments[0].querySelectorAll('.section')).map(text);
    expect(sections).toEqual(['Resumen', 'Novedades 3', 'Casas', 'Obras comunes', 'Económico', 'Equipo']);
    expect(text(developments[1].querySelector('.count')!)).toBe('5');
    expect(developments[1].querySelector('.sections')).toBeNull();
    expect(text(element.querySelector('.person')!)).toContain('Raúl Díaz');
    expect(element.querySelector('.create')?.getAttribute('href')).toBe('/desarrollos?nuevo=1');
  });

  it('las casas aparecen sólo dentro de Casas o de una casa, hasta doce, con "Ver todas"', () => {
    const units = Array.from({ length: 14 }, (_, i) => aUnit({ id: 'u' + i, code: 'Casa ' + (i + 1) }));
    expect(render({ units }).querySelector('.houses')).toBeNull();

    TestBed.resetTestingModule();
    const element = render({ units, inHouses: true });
    const houses = Array.from(element.querySelectorAll('.houses .house'));
    expect(houses).toHaveLength(MENU_HOUSES + 1);
    expect(text(houses[0])).toBe('Casa 1');
    expect(houses[0].getAttribute('href')).toBe('/obras/d1/casas/u0');
    expect(text(houses.at(-1)!)).toBe('Ver todas (14)');
    expect(houses.at(-1)!.getAttribute('href')).toBe('/obras/d1/casas');
  });
});
