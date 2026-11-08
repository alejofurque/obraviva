import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/session/user-context.service';
import { DISPLAY_FONT_STYLESHEET } from '../../shared/ui/display-font';
import { LoginComponent } from './login.component';

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Ingreso', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let element: HTMLElement;
  let login: ReturnType<typeof vi.fn>;
  let restoreSession: ReturnType<typeof vi.fn>;
  const restoreProblem = signal<string | null>(null);

  function render(query: Record<string, string> = {}): void {
    login = vi.fn(() => of({}));
    restoreSession = vi.fn(() => of('offline'));
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
        { provide: AuthService, useValue: { login, restoreSession, restoreProblem: restoreProblem.asReadonly() } },
        { provide: UserContextService, useValue: { ensureLoaded: () => of(undefined), homeUrl: () => ['/desarrollos'] } },
      ],
    });
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  function type(selector: string, value: string): void {
    const input = element.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  const submit = () => element.querySelector<HTMLButtonElement>('button[type=submit]')!;

  /** Adónde navegó el ingreso, como texto. */
  function landedOn(navigate: ReturnType<typeof vi.spyOn>): string {
    return TestBed.inject(Router).serializeUrl(navigate.mock.calls[0][0] as UrlTree);
  }

  afterEach(() => restoreProblem.set(null));
  afterEach(() => document.head.querySelectorAll(`link[href="${DISPLAY_FONT_STYLESHEET}"]`).forEach(link => link.remove()));

  it('"Entrar" se habilita recién con correo y contraseña', () => {
    render();

    expect(submit().disabled).toBe(true);
    type('#email', 'familia@obraviva.test');
    expect(submit().disabled).toBe(true);
    type('#password', 'clave-de-prueba');
    expect(submit().disabled).toBe(false);
    type('#email', 'no-es-un-correo');
    expect(submit().disabled).toBe(true);
  });

  it('"Mostrar" deja ver la contraseña dentro del mismo campo y "Ocultar" la vuelve a tapar', () => {
    render();
    const reveal = element.querySelector<HTMLButtonElement>('.reveal')!;

    expect(element.querySelector<HTMLInputElement>('#password')!.type).toBe('password');
    expect(text(reveal)).toBe('Mostrar');
    reveal.click();
    fixture.detectChanges();
    expect(element.querySelector<HTMLInputElement>('#password')!.type).toBe('text');
    expect(text(reveal)).toBe('Ocultar');
    expect(reveal.getAttribute('aria-pressed')).toBe('true');
    expect(reveal.type).toBe('button');
  });

  it('entra con el correo sin espacios y va al inicio de la persona', () => {
    render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    type('#email', '  familia@obraviva.test ');
    type('#password', 'clave-de-prueba');
    element.querySelector('form')!.dispatchEvent(new Event('submit'));

    expect(login).toHaveBeenCalledWith({ email: 'familia@obraviva.test', password: 'clave-de-prueba' });
    expect(landedOn(navigate)).toBe('/desarrollos');
    // El ingreso no queda en el historial: Volver y el atrás no regresan a él.
    expect(navigate.mock.calls[0][1]).toEqual({ replaceUrl: true });
  });

  it('si se le pidió entrar yendo a otra dirección, vuelve ahí', () => {
    render({ volver: '/obras/d1/casas?estado=RESERVED' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    type('#email', 'familia@obraviva.test');
    type('#password', 'clave-de-prueba');
    element.querySelector('form')!.dispatchEvent(new Event('submit'));

    expect(landedOn(navigate)).toBe('/obras/d1/casas?estado=RESERVED');
  });

  it('una dirección a otro sitio no se sigue: va al inicio', () => {
    render({ volver: '//otro-sitio.com/robar' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    type('#email', 'familia@obraviva.test');
    type('#password', 'clave-de-prueba');
    element.querySelector('form')!.dispatchEvent(new Event('submit'));

    expect(landedOn(navigate)).toBe('/desarrollos');
  });

  it('con la sesión guardada pero el servidor sin responder lo dice y deja reintentar', () => {
    restoreProblem.set('No hay conexión con el servidor. Tu sesión sigue guardada: reintentá en un momento.');
    render();

    expect(text(element.querySelector('.restore p')!)).toContain('Tu sesión sigue guardada');
    element.querySelector<HTMLButtonElement>('.restore button')!.click();
    expect(restoreSession).toHaveBeenCalled();
  });

  it('con datos incorrectos lo dice, con el mismo texto de siempre', () => {
    render();
    login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    type('#email', 'familia@obraviva.test');
    type('#password', 'otra');
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(text(element.querySelector('[role=alert]')!)).toBe('Correo o contraseña incorrectos.');
    expect(submit().disabled).toBe(false);
  });

  it('las tarjetas son decorativas y la serif de los títulos se carga local, una sola vez', () => {
    render();

    expect(element.querySelector('app-login-showcase')!.getAttribute('aria-hidden')).toBe('true');
    expect(text(element.querySelector('.promise-title')!)).toBe('Ver. Seguir. Mostrar.');
    expect(document.head.querySelectorAll(`link[href="${DISPLAY_FONT_STYLESHEET}"]`)).toHaveLength(1);
    TestBed.createComponent(LoginComponent).detectChanges();
    expect(document.head.querySelectorAll(`link[href="${DISPLAY_FONT_STYLESHEET}"]`)).toHaveLength(1);
  });
});
