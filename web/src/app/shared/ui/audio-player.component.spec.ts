import { TestBed } from '@angular/core/testing';
import { provideAppIcons } from './icons';
import { AudioPlayerComponent, clockOf } from './audio-player.component';

describe('Reproductor de audio propio', () => {
  it('el tiempo va en minutos y segundos', () => {
    expect(clockOf(0)).toBe('0:00');
    expect(clockOf(65.7)).toBe('1:05');
    expect(clockOf(Number.NaN)).toBe('0:00');
    expect(clockOf(Number.POSITIVE_INFINITY)).toBe('0:00');
  });

  it('no usa los controles del navegador: un botón para escuchar, la posición y el tiempo', () => {
    TestBed.configureTestingModule({ imports: [AudioPlayerComponent], providers: [provideAppIcons()] });
    const fixture = TestBed.createComponent(AudioPlayerComponent);
    fixture.componentRef.setInput('src', 'https://s/parte.ogg');
    fixture.componentRef.setInput('label', 'el parte');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('audio')?.hasAttribute('controls')).toBe(false);
    expect(element.querySelector('button')?.getAttribute('aria-label')).toBe('Escuchar el parte');
    expect(element.querySelector('input[type="range"]')?.getAttribute('aria-label')).toBe('Posición en el parte');
    expect(element.querySelector('.time')?.textContent?.trim()).toBe('0:00 / 0:00');
  });
});
