import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { aPlaceMonth, aPublicMedia } from '../../testing/fixtures';
import { TourMonthMediaComponent } from './tour-month-media.component';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Fotos de este mes, en el recorrido', () => {
  function render() {
    TestBed.configureTestingModule({
      imports: [TourMonthMediaComponent],
      providers: [{ provide: LOCALE_ID, useValue: 'es-AR' }],
    });
    const fixture = TestBed.createComponent(TourMonthMediaComponent);
    fixture.componentRef.setInput('place', 'Living');
    fixture.componentRef.setInput('media', aPlaceMonth('2026-10', {
      photos: [aPublicMedia('https://s/a.jpg', { takenOn: '2026-10-14' }), aPublicMedia('https://s/b.jpg')],
      videos: [aPublicMedia('https://s/c.mp4', { kind: 'VIDEO', contentType: 'video/mp4' })],
    }));
    fixture.detectChanges();
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  it('arranca plegada, con cuántas fotos y videos hay, y se despliega sin tapar la escena', () => {
    const { fixture, element } = render();
    const toggle = element.querySelector<HTMLButtonElement>('.toggle')!;

    expect(text(toggle)).toBe('Fotos de este mes · 3');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('.sheet')).toBeNull();

    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(element.querySelectorAll('.sheet img')).toHaveLength(2);
    expect(element.querySelector('.sheet video')?.getAttribute('preload')).toBe('metadata');
  });

  it('una foto se amplía con su fecha', () => {
    const { fixture, element } = render();
    element.querySelector<HTMLButtonElement>('.toggle')!.click();
    fixture.detectChanges();

    element.querySelector<HTMLButtonElement>('.sheet .shot')!.click();
    fixture.detectChanges();
    expect(element.querySelector('dialog img')?.getAttribute('src')).toBe('https://s/a.jpg');
    expect(text(element.querySelector('dialog')!)).toContain('14 de octubre de 2026');
  });
});
