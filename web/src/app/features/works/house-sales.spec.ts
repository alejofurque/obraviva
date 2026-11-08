import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Unit } from '../../core/api/api.models';
import { aShareLink, aUnit } from '../../testing/fixtures';
import { linkMeta, linkState } from './public-links.component';
import { HouseSalesComponent } from './house-sales.component';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

const MEMBER = {
  id: 'm1', unitId: 'u1', userId: 'b1', email: 'marta@obra.test', fullName: 'Marta Compradora', createdAt: '2026-10-01T10:00:00Z',
};

describe('Compradores de una casa', () => {
  let fixture: ComponentFixture<HouseSalesComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HouseSalesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { reload: () => {} } },
        {
          provide: HouseStore,
          useValue: { unit: signal(aUnit()).asReadonly(), unitId: computed(() => 'u1'), reload: () => {} },
        },
      ],
    });
    fixture = TestBed.createComponent(HouseSalesComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/units/u1/members').flush([MEMBER]);
    http.expectOne('/api/units/u1/share-links').flush([]);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  function type(selector: string, value: string): void {
    const input = element.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  /** Ningún texto ni campo de la pantalla conserva la contraseña. */
  function expectNowhere(password: string): void {
    expect(text(element)).not.toContain(password);
    expect(Array.from(element.querySelectorAll('input')).map(input => input.value)).not.toContain(password);
  }

  describe('al crear la cuenta', () => {
    it('con contraseña escrita, la manda y después no la muestra en ningún lado', () => {
      type('#buyer-email', 'nueva@obra.test');
      type('#buyer-name', 'Nueva Compradora');
      type('#buyer-password', 'Obra-Nogal-26');
      button('Vincular comprador').click();

      const request = http.expectOne(req => req.method === 'POST' && req.url === '/api/units/u1/members');
      expect(request.request.body).toEqual({ email: 'nueva@obra.test', fullName: 'Nueva Compradora', password: 'Obra-Nogal-26' });
      request.flush({ ...MEMBER, id: 'm2', accountCreated: true, initialPassword: null });
      http.expectOne('/api/units/u1/members').flush([MEMBER]);
      fixture.detectChanges();

      expect(element.querySelector('app-initial-password')).toBeNull();
      expectNowhere('Obra-Nogal-26');
    });

    it('sin contraseña no la manda y muestra la generada una vez', () => {
      type('#buyer-email', 'nueva@obra.test');
      type('#buyer-name', 'Nueva Compradora');
      button('Vincular comprador').click();

      const request = http.expectOne(req => req.method === 'POST' && req.url === '/api/units/u1/members');
      expect(request.request.body).toEqual({ email: 'nueva@obra.test', fullName: 'Nueva Compradora' });
      request.flush({ ...MEMBER, id: 'm2', accountCreated: true, initialPassword: 'abcd-efgh-jkmn' });
      http.expectOne('/api/units/u1/members').flush([MEMBER]);
      fixture.detectChanges();

      expect(text(element.querySelector('app-initial-password')!)).toContain('abcd-efgh-jkmn');
    });

    it('una contraseña de menos de 8 caracteres no se puede enviar', () => {
      type('#buyer-email', 'nueva@obra.test');
      type('#buyer-password', 'corta1');

      expect(button('Vincular comprador').disabled).toBe(true);
    });

    it('el botón muestra y oculta lo escrito', () => {
      const input = element.querySelector<HTMLInputElement>('#buyer-password')!;
      expect(input.type).toBe('password');

      button('Mostrar').click();
      fixture.detectChanges();
      expect(input.type).toBe('text');
      button('Ocultar').click();
      fixture.detectChanges();
      expect(input.type).toBe('password');
    });

    it('el texto de ayuda dice qué pasa si queda vacía', () => {
      expect(text(element.querySelector('.buyer')!)).toContain('Si la dejás vacía, te mostramos una contraseña generada.');
    });
  });

  describe('"Nueva contraseña"', () => {
    it('con una escrita, la guarda y no la vuelve a mostrar: sólo avisa que quedó', () => {
      button('Nueva contraseña').click();
      fixture.detectChanges();
      type('#new-password-m1', 'Otra-Clave-2026');
      button('Guardar contraseña').click();

      const request = http.expectOne('/api/unit-members/m1/reset-password');
      expect(request.request.body).toEqual({ password: 'Otra-Clave-2026' });
      request.flush({ initialPassword: null });
      fixture.detectChanges();

      expect(element.querySelector('#new-password-m1')).toBeNull();
      expect(element.querySelector('app-initial-password')).toBeNull();
      expect(text(element.querySelector('.status')!)).toContain('Marta Compradora ya puede entrar con la contraseña nueva');
      expectNowhere('Otra-Clave-2026');
    });

    it('vacía, pide una generada y la muestra una vez', () => {
      button('Nueva contraseña').click();
      fixture.detectChanges();
      button('Guardar contraseña').click();

      const request = http.expectOne('/api/unit-members/m1/reset-password');
      expect(request.request.body).toEqual({});
      request.flush({ initialPassword: 'pqrs-tuvw-xyz2' });
      fixture.detectChanges();

      expect(text(element.querySelector('app-initial-password')!)).toContain('pqrs-tuvw-xyz2');
    });

    it('corta no se puede guardar, y cancelar cierra el campo sin enviar nada', () => {
      button('Nueva contraseña').click();
      fixture.detectChanges();
      type('#new-password-m1', '1234567');
      expect(button('Guardar contraseña').disabled).toBe(true);

      button('Cancelar').click();
      fixture.detectChanges();
      expect(element.querySelector('#new-password-m1')).toBeNull();
    });
  });
});

describe('Estado comercial y enlaces de una casa', () => {
  let fixture: ComponentFixture<HouseSalesComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(unit: Unit, links = [aShareLink()]): void {
    TestBed.configureTestingModule({
      imports: [HouseSalesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { reload: () => {} } },
        { provide: HouseStore, useValue: { unit: signal(unit).asReadonly(), unitId: computed(() => 'u1'), reload: () => {} } },
      ],
    });
    fixture = TestBed.createComponent(HouseSalesComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/units/u1/members').flush([]);
    http.expectOne('/api/units/u1/share-links').flush(links);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  const button = (label: string) =>
    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  it('pasar a reservada propone la fecha de hoy y guarda estado y día; la ayuda no habla de seña', () => {
    render(aUnit());
    expect(element.querySelector('#status-on')).toBeNull();
    // Palabra entera: "contraseña" no cuenta.
    expect(text(element).toLowerCase().split(/[^a-záéíóúñ]+/)).not.toContain('seña');

    button('Reservada').click();
    fixture.detectChanges();
    const day = element.querySelector<HTMLInputElement>('#status-on')!;
    expect(day.value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    day.value = '2026-03-12';
    day.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('.status-row')!.dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/units/u1/sales-status');
    expect(request.request.body).toEqual({ status: 'RESERVED', on: '2026-03-12' });
    request.flush(aUnit({ salesStatus: 'RESERVED', salesStatusOn: '2026-03-12' }));
    http.verify();
  });

  it('una casa vendida sin fecha cargada lo dice, y guardar sin cambios no manda nada', () => {
    render(aUnit({ salesStatus: 'SOLD', salesStatusOn: null }));
    expect(text(element.querySelector('.status-row')!)).toContain('Sin fecha cargada.');

    element.querySelector<HTMLFormElement>('.status-row')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    http.verify();
    expect(text(element)).toContain('Guardado.');
  });

  it('los enlaces dicen quién los creó, cuándo vencen y su último acceso, y se cortan con confirmación', () => {
    render(aUnit(), [
      aShareLink({ id: 'l1', label: 'Para la familia', createdByName: 'Lucía Benítez', createdAt: '2026-10-18T12:00:00Z', lastAccessedAt: '2026-10-23T12:00:00Z' }),
      aShareLink({ id: 'l2', label: 'Visita al arquitecto', active: false, revokedAt: '2026-10-03T12:00:00Z' }),
    ]);
    const rows = Array.from(element.querySelectorAll('app-public-links .link'));

    expect(text(rows[0])).toContain('Activo');
    expect(text(rows[0])).toContain('Creado por Lucía Benítez · 18 oct');
    expect(text(rows[1])).toContain('Cortado el 3 oct');
    expect(rows[1].querySelector('button')).toBeNull();
    expect(element.querySelector('app-public-links .head a')?.getAttribute('href')).toBe('/mi-propiedad/u1/compartir?obra=d1');

    button('Cortar').click();
    fixture.detectChanges();
    button('Cortar enlace').click();
    http.expectOne(request => request.method === 'POST' && request.url === '/api/share-links/l1/revoke').flush(aShareLink({ active: false }));
    http.expectOne('/api/units/u1/share-links').flush([]);
    http.verify();
  });
});

describe('Lectura de un enlace', () => {
  it('su estado y su línea: quién, cuándo, si vence y el último acceso', () => {
    expect(linkState(aShareLink())).toBe('Activo');
    expect(linkState(aShareLink({ active: false, expiresAt: '2026-09-30T00:00:00Z' }))).toBe('Vencido');
    expect(linkMeta(aShareLink({ createdByYou: true, createdAt: '2026-10-18T12:00:00Z', expiresAt: '2026-11-30T12:00:00Z' }), '2026-10-25'))
      .toBe('Creado por vos · 18 oct · vence el 30 nov · sin visitas todavía');
    expect(linkMeta(aShareLink({ active: false, expiresAt: '2026-09-30T12:00:00Z' }))).toBe('Venció el 30 sep');
  });
});

describe('Compradores de una casa: cargando, error y nadie vinculado', () => {
  function render(): { fixture: ComponentFixture<HouseSalesComponent>; http: HttpTestingController; element: HTMLElement } {
    TestBed.configureTestingModule({
      imports: [HouseSalesComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { reload: () => {} } },
        { provide: HouseStore, useValue: { unit: signal(aUnit()).asReadonly(), unitId: computed(() => 'u1'), reload: () => {} } },
      ],
    });
    const fixture = TestBed.createComponent(HouseSalesComponent);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), element: fixture.nativeElement as HTMLElement };
  }

  it('mientras carga no dice que no hay nadie; si falla, lo dice y deja reintentar', () => {
    const { fixture, http, element } = render();
    expect(text(element)).toContain('Cargando compradores…');
    expect(text(element)).not.toContain('Nadie vinculado todavía.');

    http.expectOne('/api/units/u1/members').flush({ message: 'Se cayó' }, { status: 500, statusText: 'x' });
    http.expectOne('/api/units/u1/share-links').flush([]);
    fixture.detectChanges();
    expect(text(element)).toContain('Se cayó');
    expect(text(element)).not.toContain('Nadie vinculado todavía.');

    Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === 'Reintentar')!.click();
    http.expectOne('/api/units/u1/members').flush([]);
    fixture.detectChanges();
    expect(text(element)).toContain('Nadie vinculado todavía.');
  });
});
