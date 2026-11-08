import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Development, MyUnitSummary } from '../api/api.models';
import { AuthService } from '../auth/auth.service';
import { UserContextService } from './user-context.service';
import { aDevelopment } from '../../testing/fixtures';

function development(id: string): Development {
  return aDevelopment({ id, organizationId: 'org', name: `Obra ${id}` });
}

function unit(unitId: string): MyUnitSummary {
  return { unitId, unitCode: `U${unitId}`, unitDescription: null, developmentName: 'Los Aromos', developmentAddress: null, developmentKind: 'MULTI_UNIT' };
}

describe('UserContextService', () => {
  let http: HttpTestingController;
  let context: UserContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: () => ({ id: 'u1', email: 'a@b.c', fullName: 'Ana' }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    context = TestBed.inject(UserContextService);
  });

  afterEach(() => http.verify());

  async function load(developments: Development[], units: MyUnitSummary[]): Promise<void> {
    const loaded = firstValueFrom(context.ensureLoaded());
    http.expectOne('/api/developments').flush(developments);
    http.expectOne('/api/my-units').flush(units);
    await loaded;
  }

  it('un comprador con una sola propiedad entra directo a ella', async () => {
    await load([], [unit('7')]);
    expect(context.homeUrl()).toEqual(['/mi-propiedad', '7']);
  });

  it('un comprador con varias propiedades entra a su listado', async () => {
    await load([], [unit('7'), unit('8')]);
    expect(context.homeUrl()).toEqual(['/mis-propiedades']);
  });

  it('quien sólo administra obras entra a sus obras', async () => {
    await load([development('1')], []);
    expect(context.homeUrl()).toEqual(['/desarrollos']);
  });

  it('el desarrollista entra a sus desarrollos y no tiene trabajo de campo', async () => {
    await load([development('1')], []);
    expect(context.homeUrl()).toEqual(['/desarrollos']);
    expect(context.supervises()).toBe(false);
    expect(context.onlySupervises()).toBe(false);
  });

  it('quien sólo es encargado entra directo a su trabajo del día', async () => {
    await load([aDevelopment({ id: '1', role: 'MEMBER' }), aDevelopment({ id: '2', role: 'MEMBER' })], []);
    expect(context.homeUrl()).toEqual(['/hoy']);
    expect(context.supervises()).toBe(true);
    expect(context.onlySupervises()).toBe(true);
  });

  it('quien es dueño de un desarrollo y encargado de otro va a sus desarrollos, con trabajo de campo', async () => {
    await load([aDevelopment({ id: '1', role: 'OWNER' }), aDevelopment({ id: '2', role: 'MEMBER' })], []);
    expect(context.homeUrl()).toEqual(['/desarrollos']);
    expect(context.supervises()).toBe(true);
    expect(context.onlySupervises()).toBe(false);
  });

  it('quien tiene obras y propiedades entra al inicio que las reúne', async () => {
    await load([development('1')], [unit('7')]);
    expect(context.homeUrl()).toEqual(['/inicio']);
  });

  it('quien todavía no tiene nada entra al inicio, que lo explica', async () => {
    await load([], []);
    expect(context.homeUrl()).toEqual(['/inicio']);
  });

  it('no vuelve a pedir el contexto del mismo usuario', async () => {
    await load([development('1')], []);
    await firstValueFrom(context.ensureLoaded());
    http.expectNone('/api/developments');
  });
});
