import { HttpErrorResponse } from '@angular/common/http';
import { errorMessageOf } from './error-message';

describe('errorMessageOf', () => {
  it('usa el mensaje que redactó el backend', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { message: 'Ya existe una unidad con el código \'3B\' en esta obra' },
    });

    expect(errorMessageOf(error, 'No se pudo crear la unidad.'))
      .toBe('Ya existe una unidad con el código \'3B\' en esta obra');
  });

  it('sin conexión dice eso, que es lo accionable', () => {
    const error = new HttpErrorResponse({ status: 0 });

    expect(errorMessageOf(error, 'No se pudo crear la unidad.')).toContain('No hay conexión');
  });

  it('sin mensaje del backend usa el texto de respaldo', () => {
    expect(errorMessageOf(new HttpErrorResponse({ status: 500, error: null }), 'Respaldo')).toBe('Respaldo');
    expect(errorMessageOf(new HttpErrorResponse({ status: 500, error: { message: '  ' } }), 'Respaldo')).toBe('Respaldo');
    expect(errorMessageOf(new Error('otro'), 'Respaldo')).toBe('Respaldo');
  });
});
