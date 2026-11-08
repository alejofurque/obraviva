import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { aShareLink } from '../../testing/fixtures';
import { PropertyShareComponent } from './property-share.component';
import { PropertyViewStore } from './property-view.store';

registerLocaleData(localeEsAr);

const link = aShareLink;

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

/** Compartir es pantalla del comprador: nada en rojo, tampoco un error. */
describe('Compartir', () => {
  let fixture: ComponentFixture<PropertyShareComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PropertyShareComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: PropertyViewStore, useValue: { source: signal({ kind: 'private', unitId: 'u1' }).asReadonly() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PropertyShareComponent);
    fixture.detectChanges();
  });

  const element = () => fixture.nativeElement as HTMLElement;

  it('un enlace revocado lleva la píldora gris, no la de peligro', () => {
    http.expectOne('/api/units/u1/share-links').flush([
      link({ id: 'l1' }),
      link({ id: 'l2', label: 'Arquitecta', active: false, revokedAt: '2026-10-10T12:00:00Z' }),
    ]);
    fixture.detectChanges();

    const pills = Array.from(element().querySelectorAll('.ov-pill'));
    expect(pills.map(text)).toEqual(['Activo', 'Revocado']);
    expect(pills[1].className).toBe('ov-pill');
    expect(element().querySelector('[class*="danger"]')).toBeNull();
  });

  it('si no se puede generar el enlace, lo dice con ícono y el motivo, sin rojo', () => {
    http.expectOne('/api/units/u1/share-links').flush([]);
    fixture.detectChanges();

    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    http.expectOne({ method: 'POST', url: '/api/units/u1/share-links' })
      .flush({ message: 'La casa todavía no tiene nada publicado.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    const error = element().querySelector('.form-error')!;
    expect(error.getAttribute('role')).toBe('alert');
    expect(error.querySelector('mat-icon')?.getAttribute('svgIcon')).toBe('error');
    expect(text(error)).toBe('La casa todavía no tiene nada publicado.');
    expect(element().querySelector('.ov-error')).toBeNull();
  });

  it('si no se pueden cargar los enlaces, el estado de error va en tono neutro', () => {
    http.expectOne('/api/units/u1/share-links')
      .flush({ message: 'Sin conexión con el servidor.' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    const state = element().querySelector('app-error-state')!;
    expect(state.classList).toContain('neutral');
    expect(text(state)).toContain('Sin conexión con el servidor.');
  });
});
