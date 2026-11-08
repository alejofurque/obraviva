import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, Type, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  OwnerPreview, PublicCapture, PublicFront, PublicMedia, PublicPlace, PublicProgressEntry, PublicUnitView,
} from '../../core/api/api.models';
import { PropertyDocumentsComponent } from './property-documents.component';
import { PropertyHistoryComponent } from './property-history.component';
import { PropertyHomeComponent } from './property-home.component';
import { PropertyPlaceDetailComponent } from './property-place-detail.component';
import { PropertyPlansComponent } from './property-plans.component';
import { PropertySummaryComponent } from './property-summary.component';
import { PropertyViewStore } from './property-view.store';
import { UserContextService } from '../../core/session/user-context.service';
import { AuthService } from '../../core/auth/auth.service';
import { aPlaceMonth, aPublicMedia, aPublicStage, publicDetails, publicDevelopment } from '../../testing/fixtures';

const VIEW: PublicUnitView = {
  unitCode: 'Casa 12',
  unitDescription: 'Tres dormitorios',
  developmentName: 'Los Aromos',
  developmentAddress: 'Córdoba',
  developmentKind: 'MULTI_UNIT',
  details: publicDetails({
    areaM2: 126.5, bedrooms: 3, bathrooms: null, features: ['Cochera', 'Patio'],
    estimatedDelivery: '2027-03', coverUrl: null, coverThumbUrl: null, coverScreenUrl: null,
  }),
  overallProgressPercent: 64,
  currentStage: aPublicStage('Instalaciones', 3, 'DELAYED', 40),
  stages: [
    aPublicStage('Fundaciones', 1, 'COMPLETED', 100),
    aPublicStage('Mampostería', 2, 'COMPLETED', 100),
    aPublicStage('Instalaciones', 3, 'DELAYED', 40),
    aPublicStage('Terminaciones', 4, 'PENDING', 0),
  ],
  timeline: [
    {
      date: '2026-09-10', stageName: 'Instalaciones', description: 'Comenzó el tendido eléctrico.',
      source: 'AUDIO', corrected: true, common: false, stageChange: null,
      photos: [{ url: 'https://s/cocina.jpg', contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-09-10T15:00:00Z', entryDate: '2026-09-10', stageName: 'Instalaciones', takenOn: null, thumbUrl: null, screenUrl: null }],
    },
    { date: '2026-02-10', stageName: 'Fundaciones', description: 'Se hormigonó la platea.', source: 'MANUAL', corrected: false, photos: [], common: false, stageChange: null },
  ],
  captures: [],
  gallery: [
    { url: 'https://s/cocina.jpg', contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-09-10T15:00:00Z', entryDate: '2026-09-10', stageName: 'Instalaciones', takenOn: null, thumbUrl: null, screenUrl: null },
    { url: 'https://s/platea.jpg', contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-02-10T15:00:00Z', entryDate: '2026-02-10', stageName: 'Fundaciones', takenOn: null, thumbUrl: null, screenUrl: null },
  ],
  plans: [
    {
      name: 'Planta baja', widthM: 12, depthM: 9, imageUrl: null,
      rooms: [
        { name: 'Living', polygon: [0, 0, 4, 0, 4, 5, 0, 5], areaM2: 20, declaredArea: false },
        { name: 'Cocina', polygon: [4, 0, 7, 0, 7, 3, 4, 3], areaM2: 9.1, declaredArea: true },
      ],
    },
  ],
  comparisons: [
    { title: 'Living', renderUrl: 'https://s/render.jpg', renderThumbUrl: null, renderScreenUrl: null, photoUrl: 'https://s/living.jpg', photoDate: '2026-09-10', photoStageName: 'Instalaciones', common: false },
  ],
  showcase: [],
  development: publicDevelopment(),
  sectors: [],
  places: [],
  front: null,
};

// Mismo idioma que la aplicación: decimales con coma y fechas en castellano.
registerLocaleData(localeEsAr);

function mount<T>(component: Type<T>, isPublic = false): ComponentFixture<T> {
  const store = {
    view: signal(VIEW).asReadonly(),
    isPublic: computed(() => isPublic),
    basePath: computed(() => (isPublic ? ['/p', 'token'] : ['/mi-propiedad', 'u1'])),
    preview: signal<OwnerPreview | null>(null).asReadonly(),
    unitId: computed(() => (isPublic ? null : 'u1')),
    navQuery: signal({}).asReadonly(),
    homeLabel: computed(() => (isPublic ? 'La casa' : 'Mi casa')),
  };
  TestBed.configureTestingModule({
    imports: [component],
    providers: [
      provideRouter([]),
      { provide: LOCALE_ID, useValue: 'es-AR' },
      { provide: PropertyViewStore, useValue: store },
      contextWith(null), SESSION, provideHttpClient(), provideHttpClientTesting(),
    ],
  });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return fixture;
}

function render<T>(component: Type<T>, isPublic = false): HTMLElement {
  return mount(component, isPublic).nativeElement as HTMLElement;
}

/** El Resumen de "Mi propiedad" con otra vista que la de base. */
function renderWith(view: PublicUnitView, renewAfterFailure = (_url: string) => {},
                    preview: OwnerPreview | null = null, role: 'OWNER' | 'MEMBER' = 'OWNER'): HTMLElement {
  return mountWith(view, renewAfterFailure, preview, role).nativeElement as HTMLElement;
}

/** La persona de la sesión: el saludo de "Mi casa" usa su nombre de pila. */
const SESSION = { provide: AuthService, useValue: { currentUser: signal({ id: 'b1', fullName: 'Lucía Paz', email: 'lucia@x.test' }).asReadonly() } };

/** El rol de quien mira en cada obra, como lo carga la sesión. */
function contextWith(role: 'OWNER' | 'MEMBER' | null) {
  return { provide: UserContextService, useValue: { developments: signal(role ? [{ id: 'd1', role }] : []).asReadonly() } };
}

/**
 * {@code preview} es la marca que manda el backend en "Ver como el comprador"; nula, la vista
 * es la de un comprador real.
 */
function mountWith(view: PublicUnitView, renewAfterFailure = (_url: string) => {},
                   preview: OwnerPreview | null = null, role: 'OWNER' | 'MEMBER' = 'OWNER'): ComponentFixture<PropertySummaryComponent> {
  const store = {
    view: signal(view).asReadonly(),
    isPublic: computed(() => false),
    basePath: computed(() => ['/mi-propiedad', 'u1']),
    preview: signal(preview).asReadonly(),
    unitId: computed(() => 'u1'),
    renewAfterFailure,
  };
  TestBed.configureTestingModule({
    imports: [PropertySummaryComponent],
    providers: [
      provideRouter([]), { provide: LOCALE_ID, useValue: 'es-AR' }, { provide: PropertyViewStore, useValue: store },
      contextWith(preview ? role : null), SESSION, provideHttpClient(), provideHttpClientTesting(),
    ],
  });
  const fixture = TestBed.createComponent(PropertySummaryComponent);
  fixture.detectChanges();
  return fixture;
}

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';

function capture(date: string): PublicCapture {
  return {
    date, zone: null, description: null, stageName: null, common: false, splatUrl: `https://s/${date}`,
    transform: { positionX: 0, positionY: 0, positionZ: 0, rotationX: 0, rotationY: 0, rotationZ: 0, scale: 1 },
  };
}

describe('Pantallas de la propiedad', () => {
  describe('Mi casa', () => {
    it('la portada saluda por el nombre, dice lo que es cierto hoy y trae el último avance tal cual', () => {
      const hero = render(PropertySummaryComponent).querySelector('app-mi-casa-hero')!;

      expect(text(hero.querySelector('.greeting')!)).toBe('Hola, Lucía');
      expect(text(hero.querySelector('h1')!)).toBe('Tu casa avanza.');
      expect(hero.querySelector('h1')?.classList).toContain('ov-display');
      expect(text(hero.querySelector('.sentence')!)).toBe('Comenzó el tendido eléctrico.');
      expect(text(hero.querySelector('.context')!)).toContain('Casa 12 · Los Aromos · última novedad');
    });

    it('las tres tarjetas: el avance, la entrega con la demora y su motivo (nunca en rojo), y lo que se hace ahora', () => {
      const status = render(PropertySummaryComponent).querySelector('app-mi-casa-status')!;
      const cards = Array.from(status.querySelectorAll('.card')).map(text);

      expect(status.querySelector('.ring')?.getAttribute('aria-label')).toBe('64 por ciento de avance');
      expect(cards[0]).toContain('2 de 4 etapas terminadas');
      expect(cards[1]).toContain('Marzo 2027');
      expect(text(status.querySelector('.badge')!)).toBe('Con demora');
      expect(text(status.querySelector('.reason')!)).toBe('Instalaciones lleva más de lo previsto.');
      expect(cards[2]).toContain('Instalaciones');
      expect(cards[2]).toContain('40%');
      expect(text(status)).not.toContain('Subió');
    });

    it('lo último de la casa: los hechos del mes con sus fotos, lo que viene y el camino de etapas', () => {
      const element = render(PropertySummaryComponent);
      const month = element.querySelector('app-mi-casa-month')!;

      expect(text(month.querySelector('.eyebrow')!)).toBe('Septiembre 2026');
      expect(text(month.querySelector('.fact-title')!)).toBe('Instalaciones');
      expect(month.querySelector('.tile img')?.getAttribute('src')).toBe('https://s/cocina.jpg');
      expect(text(element.querySelector('app-mi-casa-upcoming h2')!)).toBe('Terminaciones');
      expect(Array.from(element.querySelectorAll('app-mi-casa-journey .cell .state')).map(text))
        .toEqual(['Terminada', 'Terminada', 'En curso', 'Por venir']);
      expect(element.querySelector('app-mi-casa-journey a')?.getAttribute('href')).toBe('/mi-propiedad/u1/etapas');
    });

    it('el cierre: la casa con sus datos cargados, y el enlace para la familia', () => {
      const closing = render(PropertySummaryComponent).querySelector('app-mi-casa-closing')!;
      const facts = text(closing.querySelector('.facts')!);

      expect(facts).toContain('126,5 m²');
      expect(facts).toContain('Dormitorios');
      expect(facts).not.toContain('Baño');
      expect(Array.from(closing.querySelectorAll('a')).find(link => text(link) === 'Planos y el barrio')?.getAttribute('href'))
        .toBe('/mi-propiedad/u1/casa');
      expect(Array.from(closing.querySelectorAll('a')).find(link => text(link) === 'Crear enlace')?.getAttribute('href'))
        .toBe('/mi-propiedad/u1/compartir');
    });

    it('en el enlace público: sin saludo, sin compartir y sin documentos', () => {
      const element = render(PropertySummaryComponent, true);

      expect(element.querySelector('app-mi-casa-hero .greeting')).toBeNull();
      expect(text(element)).not.toContain('Mostrásela');
      expect(text(element)).not.toContain('Tus documentos');
    });

    it('sin portada y sin recorridos, usa la foto más reciente y no ofrece recorrer', () => {
      const element = render(PropertySummaryComponent);

      expect(element.querySelector<HTMLImageElement>('.hero img')?.src).toBe('https://s/cocina.jpg');
      expect(element.querySelector('.hero a.tour')).toBeNull();
    });

    it('con recorridos, ofrece recorrer en 3D desde la portada, una sola vez', () => {
      const element = renderWith({ ...VIEW, captures: [capture('2026-04-12'), capture('2026-09-05')] });

      const buttons = element.querySelectorAll<HTMLAnchorElement>('a.tour');
      expect(buttons).toHaveLength(1);
      expect(buttons[0].closest('.hero')).not.toBeNull();
      expect(text(buttons[0])).toBe('Recorrer en 3D');
      expect(buttons[0].getAttribute('href')).toBe('/mi-propiedad/u1/recorrido');
    });

    it('con recorridos y sin ninguna imagen, la portada va sin foto y ofrece recorrer igual', () => {
      const element = renderWith({
        ...VIEW,
        captures: [capture('2026-09-05')],
        gallery: [],
        timeline: VIEW.timeline.map(entry => ({ ...entry, photos: [] })),
      });

      expect(element.querySelector('.hero img')).toBeNull();
      expect(element.querySelector('.hero a.tour')?.getAttribute('href')).toBe('/mi-propiedad/u1/recorrido');
    });
  });

  /** Un frente completo: render del frente contra la foto de hoy, y la primera de otro mes. */
  const front = (overrides: Partial<PublicFront> = {}): PublicFront => ({
    sector: 'Frente', latest: { url: 'https://s/frente-hoy.jpg', thumbUrl: null, screenUrl: null, date: '2026-10-14' },
    first: { url: 'https://s/frente-inicio.jpg', thumbUrl: null, screenUrl: null, date: '2026-03-02' }, renderUrl: 'https://s/render-frente.jpg', renderThumbUrl: null, renderScreenUrl: null,
    project: { renderUrl: 'https://s/render-frente.jpg', renderScreenUrl: null, photoUrl: 'https://s/frente-hoy.jpg', photoScreenUrl: null, photoDate: '2026-10-14' },
    ...overrides,
  });
  const empty: PublicFront = { sector: null, latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null };
  const OWNER: OwnerPreview = { developmentId: 'd1' };

  describe('Portada viva', () => {

    it('es la última foto del frente, con su lugar y su día, y el botón del recorrido', () => {
      const element = renderWith({ ...VIEW, front: front(), captures: [capture('2026-09-05')] });
      const hero = element.querySelector('.hero')!;

      expect(hero.querySelector('img')?.getAttribute('src')).toBe('https://s/frente-hoy.jpg');
      expect(text(hero.querySelector('.caption')!)).toBe('Frente · 14 oct');
      expect(text(hero.querySelector('a.tour')!)).toBe('Recorrer en 3D');
    });

    it('sin foto del frente queda la portada fija con la última novedad', () => {
      const element = renderWith({ ...VIEW, front: front({ latest: null, first: null }) });

      expect(element.querySelector('.hero img')?.getAttribute('src')).toBe('https://s/cocina.jpg');
      expect(element.querySelector('.hero .caption')).toBeNull();
    });

    it('si la foto del frente falla, pide renovar la vista', () => {
      const failed: string[] = [];
      const element = renderWith({ ...VIEW, front: front() }, url => failed.push(url));

      element.querySelector('.hero img')!.dispatchEvent(new Event('error'));
      expect(failed).toEqual(['https://s/frente-hoy.jpg']);
    });

    it('en "Ver como el comprador", sin foto del frente avisa qué falta y lleva a donde se carga', () => {
      const element = renderWith({ ...VIEW, front: front({ latest: null, first: null, project: null }) }, () => {}, OWNER);
      const notice = Array.from(element.querySelectorAll('app-preview-notice')).find(item => text(item).startsWith('Portada viva'))!;

      expect(text(notice)).toContain('Portada viva: faltan fotos del frente.');
      expect(notice.querySelector('a')?.getAttribute('href')).toBe('/hoy/u1/recorrido');
    });
  });

  describe('Tu casa, de frente', () => {
    it('render del frente y foto de hoy: compara los dos y, al elegirlo, cuando empezó', () => {
      const fixture = mountWith({ ...VIEW, front: front() });
      const element = fixture.nativeElement as HTMLElement;
      const section = element.querySelector('app-front-compare')!;

      expect(text(section.querySelector('h2')!)).toBe('Del proyecto a la realidad');
      expect(section.querySelector('img.before')?.getAttribute('src')).toBe('https://s/render-frente.jpg');
      expect(section.querySelector('img.after')?.getAttribute('src')).toBe('https://s/frente-hoy.jpg');

      Array.from(section.querySelectorAll<HTMLButtonElement>('[role="radio"]')).find(item => text(item) === 'Cuando empezó')!.click();
      fixture.detectChanges();
      expect(section.querySelector('img.before')?.getAttribute('src')).toBe('https://s/frente-inicio.jpg');
    });

    it('sólo con una comparación vieja de Proyecto y realidad, El proyecto es esa: render contra su propia foto', () => {
      const element = renderWith({
        ...VIEW,
        front: {
          ...empty,
          project: { renderUrl: 'https://s/render-viejo.jpg', renderScreenUrl: null, photoUrl: 'https://s/avance.jpg', photoScreenUrl: null, photoDate: '2026-10-09' },
        },
      });
      const section = element.querySelector('app-front-compare')!;

      expect(section.querySelector('[role="radiogroup"]')).toBeNull();
      expect(section.querySelector('img.before')?.getAttribute('src')).toBe('https://s/render-viejo.jpg');
      expect(section.querySelector('img.after')?.getAttribute('src')).toBe('https://s/avance.jpg');
      expect(text(section)).toContain('9 de octubre');
    });

    it('con una sola opción no hay selector', () => {
      const one = renderWith({ ...VIEW, front: front({ project: null, renderUrl: null }) }).querySelector('app-front-compare')!;
      expect(one.querySelector('[role="radiogroup"]')).toBeNull();
      expect(one.querySelector('img.before')?.getAttribute('src')).toBe('https://s/frente-inicio.jpg');
    });

    it('sin nada cargado, el comprador no ve la sección ni ningún aviso', () => {
      const element = renderWith({ ...VIEW, front: empty });

      expect(element.querySelector('app-front-compare section')).toBeNull();
      expect(element.querySelector('app-preview-notice')).toBeNull();
      expect(text(element)).not.toContain('falta');
    });

    it('sin nada cargado, en "Ver como el comprador" avisa qué falta, con un enlace a cada lugar', () => {
      const element = renderWith({ ...VIEW, front: empty }, () => {}, OWNER);
      const notice = Array.from(element.querySelectorAll('app-preview-notice')).find(item => text(item).startsWith('Del proyecto a la realidad'))!;

      expect(element.querySelector('app-front-compare section')).toBeNull();
      expect(text(notice)).toContain('Del proyecto a la realidad: falta marcar el frente y el render del proyecto.');
      const links = Array.from(notice.querySelectorAll('a')).map(link => link.getAttribute('href'));
      expect(links).toEqual(['/obras/d1/casas/u1/recorridos?sectores=editar', '/obras/d1/casas/u1/ficha']);
    });

    it('con el frente marcado y su foto pero sin render, el aviso pide sólo el render', () => {
      const element = renderWith({ ...VIEW, front: front({ first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null }) }, () => {}, OWNER);
      const notices = Array.from(element.querySelectorAll('app-preview-notice')).map(text);

      expect(notices).toHaveLength(1);
      expect(notices[0]).toContain('Del proyecto a la realidad: falta el render del proyecto.');
    });

    it('el encargado ve sólo lo que puede resolver: las fotos del frente, con el enlace a Recorrido de hoy', () => {
      const noPhotos = front({ latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null });
      const element = renderWith({ ...VIEW, front: noPhotos }, () => {}, OWNER, 'MEMBER');
      const notices = Array.from(element.querySelectorAll('app-preview-notice'));

      expect(notices.map(text)).toEqual([
        expect.stringContaining('Portada viva: faltan fotos del frente.'),
        expect.stringContaining('Del proyecto a la realidad: faltan fotos del frente.'),
      ]);
      expect(notices.flatMap(notice => Array.from(notice.querySelectorAll('a')).map(link => link.getAttribute('href'))))
        .toEqual(['/hoy/u1/recorrido', '/hoy/u1/recorrido']);
      expect(text(element)).not.toContain('render del proyecto');
    });

    it('al encargado no le pide marcar el frente ni el render: eso es del dueño', () => {
      const element = renderWith({ ...VIEW, front: empty }, () => {}, OWNER, 'MEMBER');

      expect(element.querySelector('app-preview-notice')).toBeNull();
    });

    it('el dueño ve todo lo que falta, con las fotos primero', () => {
      const noPhotos = front({ latest: null, first: null, renderUrl: null, renderThumbUrl: null, renderScreenUrl: null, project: null });
      const notice = Array.from(renderWith({ ...VIEW, front: noPhotos }, () => {}, OWNER, 'OWNER')
        .querySelectorAll('app-preview-notice')).find(item => text(item).startsWith('Del proyecto a la realidad'))!;

      expect(text(notice)).toContain('Del proyecto a la realidad: faltan fotos del frente y el render del proyecto.');
    });

    it('con la sección completa, ni el dueño ve avisos', () => {
      expect(renderWith({ ...VIEW, front: front() }, () => {}, OWNER).querySelector('app-preview-notice')).toBeNull();
    });

    it('ya no muestra "Así empezó", la grilla de fotos de la obra ni Proyecto y realidad sueltos', () => {
      const element = renderWith(VIEW);

      expect(text(element)).not.toContain('Así empezó');
      expect(text(element)).not.toContain('Fotos de la obra');
      expect(text(element)).not.toContain('Proyecto y realidad');
      expect(element.querySelector('app-compare-slider')).toBeNull();
    });
  });

  describe('Tu casa, lugar por lugar', () => {
    const places: PublicPlace[] = [
      {
        name: 'Frente',
        months: [
          aPlaceMonth('2026-08', { tour: true, photos: [aPublicMedia('https://s/frente-agosto.jpg', { entryDate: '2026-08-10' })] }),
          aPlaceMonth('2026-10', {
            photos: [aPublicMedia('https://s/frente-octubre.jpg', { takenOn: '2026-10-14' })],
            videos: [aPublicMedia('https://s/frente.mp4', { kind: 'VIDEO', contentType: 'video/mp4', takenOn: '2026-10-14' })],
            notes: [{ date: '2026-10-02', stageName: 'Revoques', description: 'Se terminó el revoque del frente.' }],
          }),
        ],
      },
      { name: 'Baño', months: [aPlaceMonth('2026-09', { photos: [aPublicMedia('https://s/bano.jpg')] })] },
      { name: 'General', months: [aPlaceMonth('2026-09', { photos: [aPublicMedia('https://s/general.jpg')] })] },
    ];

    function mountPlaces(renew = (_url: string) => {}, lugar: string | null = null):
        { fixture: ComponentFixture<PropertyPlaceDetailComponent>; section: Element } {
      const store = {
        view: signal({ ...VIEW, places }).asReadonly(), isPublic: computed(() => false),
        basePath: computed(() => ['/mi-propiedad', 'u1']), renewAfterFailure: renew,
        navQuery: signal({}).asReadonly(), homeLabel: computed(() => 'Mi casa'),
      };
      TestBed.configureTestingModule({
        imports: [PropertyPlaceDetailComponent],
        providers: [
          provideRouter([]), { provide: LOCALE_ID, useValue: 'es-AR' }, { provide: PropertyViewStore, useValue: store },
          { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(lugar ? { lugar } : {})) } },
        ],
      });
      const fixture = TestBed.createComponent(PropertyPlaceDetailComponent);
      fixture.detectChanges();
      return { fixture, section: (fixture.nativeElement as HTMLElement).querySelector('app-property-places')! };
    }

    it('en "Mi casa" cada lugar es una tarjeta con su foto, el 3D y sus meses, que abre el detalle', () => {
      const cards = Array.from(renderWith({ ...VIEW, places }).querySelectorAll<HTMLAnchorElement>('app-mi-casa-places a.card'));

      expect(cards.map(card => text(card.querySelector('.name')!))).toEqual(['Frente', 'Baño', 'General']);
      expect(text(cards[0].querySelector('.months')!)).toBe('Ago – Oct');
      expect(cards[0].querySelector('.badge')).not.toBeNull();
      expect(cards[0].getAttribute('href')).toBe('/mi-propiedad/u1/lugares?lugar=Frente');
    });

    it('el detalle abre en el lugar de la tarjeta', () => {
      const { section } = mountPlaces(() => {}, 'Baño');
      const chosen = Array.from(section.querySelectorAll('[aria-label="Lugares"] [role="radio"]'))
        .find(item => item.getAttribute('aria-checked') === 'true');
      expect(text(chosen!)).toBe('Baño');
    });

    const radios = (section: Element, label: string) =>
      Array.from(section.querySelectorAll<HTMLButtonElement>(`[aria-label="${label}"] [role="radio"]`));

    it('un chip por lugar con General al final, y en una tarjeta la línea de meses parada en el último', () => {
      const { section } = mountPlaces();
      const months = radios(section, 'Meses');

      expect(radios(section, 'Lugares').map(text)).toEqual(['Frente', 'Baño', 'General']);
      expect(section.querySelector('.card .timeline')).not.toBeNull();
      expect(text(section.querySelector('.when')!)).toContain('Meses con material de Frente');
      expect(months.map(text)).toEqual(['Ago 2026', 'Oct 2026']);
      expect(months[1].getAttribute('aria-checked')).toBe('true');
      expect(months.every(month => month.classList.contains('past'))).toBe(true);
    });

    it('con un solo mes la línea se muestra igual, con un punto', () => {
      const { fixture, section } = mountPlaces();
      radios(section, 'Lugares')[1].click();
      fixture.detectChanges();

      expect(radios(section, 'Meses').map(text)).toEqual(['Sep 2026']);
      expect(section.querySelectorAll('.timeline .dot')).toHaveLength(1);
    });

    it('el último mes sin 3D lo dice y ofrece el anterior, con los avances que aportaron y fotos y videos', () => {
      const { section } = mountPlaces();

      const noTour = section.querySelector('.no-tour')!;
      expect(text(noTour)).toContain('Este mes no hubo recorrido 3D');
      expect(text(noTour)).toContain('El último es de agosto de 2026.');
      const previous = noTour.querySelector('a')!;
      expect(text(previous)).toBe('Ver el de agosto 2026');
      expect(previous.getAttribute('href')).toBe('/mi-propiedad/u1/recorrido?mes=2026-08&sector=Frente');
      expect(text(section.querySelector('.note')!)).toBe('Octubre · Se terminó el revoque del frente.');
      expect(text(section.querySelector('.media-head h3')!)).toBe('Fotos y videos de octubre 2026');
      expect(text(section.querySelector('.media-head .muted')!)).toBe('1 foto · 1 video');
      expect(section.querySelector('.grid img')?.getAttribute('src')).toBe('https://s/frente-octubre.jpg');
      const video = section.querySelector<HTMLVideoElement>('.grid video')!;
      expect(video.getAttribute('preload')).toBe('metadata');
      // El mes ya está elegido: debajo de las fotos no va la fecha.
      expect(text(section.querySelector('.grid')!)).toBe('');
    });

    it('un mes con 3D lo muestra como tarjeta, con la foto del lugar de fondo, para recorrerlo en ese mes y lugar', () => {
      const { fixture, section } = mountPlaces();
      radios(section, 'Meses')[0].click();
      fixture.detectChanges();

      const tour = section.querySelector<HTMLAnchorElement>('a.tour')!;
      expect(text(tour.querySelector('.tour-tag')!)).toBe('Recorrido 3D de agosto 2026');
      expect(text(tour.querySelector('.tour-go')!)).toBe('Recorrer en 3D');
      expect(tour.querySelector('.tour-bg')?.getAttribute('src')).toBe('https://s/frente-agosto.jpg');
      expect(tour.getAttribute('href')).toBe('/mi-propiedad/u1/recorrido?mes=2026-08&sector=Frente');
      expect(radios(section, 'Meses').map(month => month.classList.contains('past'))).toEqual([true, false]);
    });

    it('un lugar que nunca tuvo 3D se sigue con fotos y videos; cambiar de lugar vuelve a su último mes', () => {
      const { fixture, section } = mountPlaces();
      radios(section, 'Meses')[0].click();
      radios(section, 'Lugares')[1].click();
      fixture.detectChanges();

      expect(radios(section, 'Meses').map(text)).toEqual(['Sep 2026']);
      expect(text(section.querySelector('.no-tour')!)).toContain('Este lugar se sigue con fotos y videos.');
      expect(section.querySelector('.grid img')?.getAttribute('src')).toBe('https://s/bano.jpg');
    });

    it('al tocar una foto la amplía; si falla, pide renovar la vista', () => {
      const failed: string[] = [];
      const { fixture, section } = mountPlaces(url => failed.push(url));

      section.querySelector<HTMLButtonElement>('.grid .shot')!.click();
      fixture.detectChanges();
      expect(section.querySelector('dialog img')?.getAttribute('src')).toBe('https://s/frente-octubre.jpg');

      section.querySelector('.grid img')!.dispatchEvent(new Event('error'));
      expect(failed).toEqual(['https://s/frente-octubre.jpg']);
    });

    it('sin material por lugar no hay sección', () => {
      expect(renderWith(VIEW).querySelector('app-mi-casa-places')).toBeNull();
    });
  });

  describe('Documentos', () => {
    it('lista primero los de la unidad y después los de la obra, con su enlace para abrirlos', () => {
      TestBed.configureTestingModule({
        imports: [PropertyDocumentsComponent],
        providers: [
          provideHttpClient(), provideHttpClientTesting(),
          { provide: LOCALE_ID, useValue: 'es-AR' },
          { provide: PropertyViewStore, useValue: { source: computed(() => ({ kind: 'private', unitId: 'u1' })) } },
        ],
      });
      const fixture = TestBed.createComponent(PropertyDocumentsComponent);
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectOne('/api/my-units/u1/documents').flush([
        { id: 'd1', unitId: null, title: 'Reglamento', category: 'REGLAMENTO', originalFilename: 'r.pdf', contentType: 'application/pdf', available: true, downloadUrl: 'https://s/r.pdf', createdAt: '2026-09-01T10:00:00Z' },
        { id: 'd2', unitId: 'u1', title: 'Boleto', category: 'CONTRATO', originalFilename: 'b.pdf', contentType: 'application/pdf', available: true, downloadUrl: 'https://s/b.pdf', createdAt: '2026-09-02T10:00:00Z' },
      ]);
      fixture.detectChanges();
      const items = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.item')).map(text);

      expect(items[0]).toContain('Boleto');
      expect(items[1]).toContain('Reglamento');
      expect(items[1]).toContain('de la obra');
      expect((fixture.nativeElement as HTMLElement).querySelector('a[href="https://s/b.pdf"]')).not.toBeNull();
    });
  });

  describe('La casa', () => {
    it('muestra la galería, la ficha completa y el barrio, sin datos vacíos', () => {
      TestBed.configureTestingModule({
        imports: [PropertyHomeComponent],
        providers: [
          provideRouter([]), { provide: LOCALE_ID, useValue: 'es-AR' },
          {
            provide: PropertyViewStore,
            useValue: {
              view: signal({
                ...VIEW,
                details: { ...VIEW.details, modelName: 'Modelo Aromo', longDescription: 'Casa de dos plantas.', finishes: 'Porcelanato' },
                showcase: [{ url: 'https://s/render.jpg', contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-09-01T10:00:00Z', entryDate: null, stageName: null, takenOn: null, thumbUrl: null, screenUrl: null }],
                development: { ...VIEW.development, locality: 'Villa Allende', amenities: ['Pileta'], builder: 'Constructora Sierras' },
              }).asReadonly(),
              isPublic: computed(() => false),
              basePath: computed(() => ['/mi-propiedad', 'u1']),
            },
          },
        ],
      });
      const fixture = TestBed.createComponent(PropertyHomeComponent);
      fixture.detectChanges();
      const element = fixture.nativeElement as HTMLElement;

      expect(element.querySelectorAll('.gallery .shot')).toHaveLength(1);
      expect(text(element.querySelector('#model-title')!)).toBe('Imágenes del modelo');
      expect(text(element.querySelector('#about-title')!)).toBe('Modelo Aromo');
      expect(text(element.querySelector('.facts')!)).toContain('126,5 m²');
      expect(text(element.querySelector('.includes')!)).toContain('Porcelanato');
      const barrio = text(element.querySelector('.barrio')!);
      expect(barrio).toContain('Villa Allende');
      expect(barrio).toContain('Pileta');
      expect(barrio).toContain('Constructora Sierras');
      expect(barrio).not.toContain('Servicios');
    });
  });

  describe('Historia', () => {
    function historyEntry(date: string, overrides: Partial<PublicProgressEntry> = {}): PublicProgressEntry {
      return { date, stageName: 'Revoques', description: 'Avance del ' + date, source: 'MANUAL', corrected: false, photos: [], common: false, stageChange: null, ...overrides };
    }

    function photoAt(url: string): PublicMedia {
      return { url, contentType: 'image/jpeg', kind: 'PHOTO', uploadedAt: '2026-09-10T15:00:00Z', entryDate: null, stageName: null, takenOn: null, thumbUrl: null, screenUrl: null };
    }

    function renderHistory(overrides: Partial<PublicUnitView> = {}, renewAfterFailure = (_url: string) => {}): { element: HTMLElement; fixture: ComponentFixture<PropertyHistoryComponent> } {
      const store = {
        view: signal({ ...VIEW, ...overrides }).asReadonly(),
        isPublic: computed(() => false),
        basePath: computed(() => ['/mi-propiedad', 'u1']),
        renewAfterFailure,
        navQuery: signal({}).asReadonly(),
      };
      TestBed.configureTestingModule({
        imports: [PropertyHistoryComponent],
        providers: [provideRouter([]), { provide: LOCALE_ID, useValue: 'es-AR' }, { provide: PropertyViewStore, useValue: store }],
      });
      const fixture = TestBed.createComponent(PropertyHistoryComponent);
      fixture.detectChanges();
      return { element: fixture.nativeElement as HTMLElement, fixture };
    }

    const timeline = [
      historyEntry('2026-10-02'), historyEntry('2026-10-20', { stageName: 'Pintura' }),
      historyEntry('2026-09-10'), historyEntry('2026-06-05'), historyEntry('2026-06-01'),
    ];

    it('agrupa por mes, del más nuevo al más viejo, con los dos más recientes abiertos', () => {
      const { element } = renderHistory({ timeline });
      const months = Array.from(element.querySelectorAll('.month'));

      expect(months.map(month => text(month.querySelector('.month-head')!))).toEqual([
        'Octubre 2026 · 2 avances', 'Septiembre 2026 · 1 avance', 'Junio 2026 · 2 avances',
      ]);
      expect(months.map(month => month.querySelector('.month-head')!.getAttribute('aria-expanded'))).toEqual(['true', 'true', 'false']);
      expect(Array.from(months[0].querySelectorAll('.day')).map(text)).toEqual(['20 oct', '2 oct']);
      expect(months[2].querySelector('.entry')).toBeNull();
    });

    it('la carga inicial va al final, "Antes del seguimiento", plegada y con mes y año en vez del día', () => {
      const initial = [
        historyEntry('2025-03-07', { source: 'INITIAL_LOAD', corrected: false }),
        historyEntry('2024-12-16', { source: 'INITIAL_LOAD', corrected: false }),
      ];
      const { element, fixture } = renderHistory({ timeline: [historyEntry('2026-10-14'), historyEntry('2026-09-10'), ...initial] });
      const months = () => Array.from(element.querySelectorAll('.month'));

      expect(months().map(month => text(month.querySelector('.month-name')!)))
        .toEqual(['Octubre 2026', 'Septiembre 2026', 'Antes del seguimiento']);
      const before = months()[2].querySelector<HTMLButtonElement>('.month-head')!;
      expect(before.getAttribute('aria-expanded')).toBe('false');
      before.click();
      fixture.detectChanges();
      expect(Array.from(months()[2].querySelectorAll('.day')).map(text)).toEqual(['dic 2024', 'mar 2025']);
    });

    it('un mes plegado se despliega al tocarlo', () => {
      const { element, fixture } = renderHistory({ timeline });
      const june = element.querySelectorAll<HTMLButtonElement>('.month-head')[2];

      june.click();
      fixture.detectChanges();
      expect(june.getAttribute('aria-expanded')).toBe('true');
      expect(element.querySelectorAll('.month')[2].querySelectorAll('.entry')).toHaveLength(2);
    });

    it('cada fila es liviana: día, etapa una sola vez, texto y procedencia, sin título ni botón', () => {
      const { element } = renderHistory();
      const first = element.querySelector('.entry')!;

      expect(text(first.querySelector('.meta')!)).toContain('10 sept');
      expect(text(first)).toContain('Desde un audio confirmado');
      expect(text(first)).toContain('Corregido');
      expect(text(first).match(/Instalaciones/g)).toHaveLength(1);
      expect(first.querySelector('h3')).toBeNull();
      expect(text(element)).not.toContain('Recorrer este momento');
    });

    it('muestra hasta cuatro miniaturas con "+N" y las amplía en el visor', () => {
      const photos = ['a', 'b', 'c', 'd', 'e', 'f'].map(name => photoAt(`https://s/${name}.jpg`));
      const { element, fixture } = renderHistory({ timeline: [historyEntry('2026-10-02', { photos })] });
      const thumbs = element.querySelectorAll<HTMLButtonElement>('.thumb');

      expect(thumbs).toHaveLength(4);
      expect(text(thumbs[3].querySelector('.more')!)).toBe('+3');
      thumbs[1].click();
      fixture.detectChanges();
      expect(element.querySelector('dialog img')?.getAttribute('src')).toBe('https://s/b.jpg');
      expect(text(element.querySelector('dialog')!)).toContain('2 de 6');
    });

    it('si una miniatura falla pide renovar la vista', () => {
      const failed: string[] = [];
      const { element } = renderHistory({}, url => failed.push(url));

      element.querySelector('.thumb img')!.dispatchEvent(new Event('error'));
      expect(failed).toEqual(['https://s/cocina.jpg']);
    });

    it('los recorridos 3D van por mes, con un chip por sector, y un mes con sólo recorridos también aparece', () => {
      const { element } = renderHistory({
        timeline: [historyEntry('2026-09-10')],
        captures: [capture('2026-10-05'), capture('2026-09-05'), capture('2026-09-05')]
          .map((item, index) => ({ ...item, zone: ['Living', 'Cocina', 'Living'][index] })),
        sectors: ['Cocina', 'Living'],
      });
      const months = Array.from(element.querySelectorAll('.month'));

      expect(text(months[0].querySelector('.month-head')!)).toBe('Octubre 2026 · solo recorridos 3D');
      expect(months[0].querySelector('.entry')).toBeNull();
      const chips = Array.from(months[1].querySelectorAll<HTMLAnchorElement>('.chip'));
      expect(chips.map(text)).toEqual(['Cocina', 'Living']);
      expect(chips[0].getAttribute('href')).toBe('/mi-propiedad/u1/recorrido?mes=2026-09&sector=Cocina');
    });

    it('un mes sin recorridos no muestra nada de 3D', () => {
      const { element } = renderHistory({ timeline });

      expect(element.querySelector('.tours')).toBeNull();
    });
  });

  describe('Planos', () => {
    it('al elegir un ambiente muestra su superficie y de dónde sale', () => {
      const fixture = mount(PropertyPlansComponent);
      const element = fixture.nativeElement as HTMLElement;
      const rooms = element.querySelectorAll<SVGGElement>('g.room');
      expect(rooms.length).toBe(2);
      // Sin nombres encima del plano: los planos reales traen los suyos y se enciman.
      expect(element.querySelector('svg text')).toBeNull();
      expect(text(element.querySelector('.detail')!)).toContain('Elegí un ambiente');

      rooms[1].dispatchEvent(new MouseEvent('click'));
      fixture.detectChanges();

      const detail = text(element.querySelector('.detail')!);
      expect(detail).toContain('Cocina');
      expect(detail).toContain('9,1 m²');
      expect(detail).toContain('plano de arquitectura');
      expect(rooms[1].getAttribute('aria-pressed')).toBe('true');
    });

    it('dibuja la planta a escala: el viewBox usa sus metros', () => {
      const svg = render(PropertyPlansComponent).querySelector('svg')!;
      expect(svg.getAttribute('viewBox')).toBe('-0.5 -0.5 13 10');
    });
  });
});
