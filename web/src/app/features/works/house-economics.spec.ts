import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DashboardResponse, ExpenseResponse, StageDashboardRow } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { aDevelopment } from '../../testing/fixtures';
import { HouseEconomicsComponent } from './house-economics.component';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
/** El texto de cada hijo separado por un espacio: Angular no deja espacios entre elementos. */
const joined = (element: Element) => Array.from(element.children).map(text).filter(Boolean).join(' ');
const plain = (value: string | null) => value?.replace(/\s+/g, ' ') ?? '';
const today = todayLocal();
const month = today.slice(0, 7);

function stageRow(overrides: Partial<StageDashboardRow>): StageDashboardRow {
  return {
    stageId: 's1', name: 'Estructura', orderIndex: 1, status: 'IN_PROGRESS', progressPercent: 40, weight: 1,
    budgetAmount: null, spentAmount: 0, budgetUsedPercent: null, trafficLight: 'NO_BUDGET', ...overrides,
  };
}

function panel(stages: StageDashboardRow[], overrides: Partial<DashboardResponse> = {}): DashboardResponse {
  return {
    developmentId: 'd1', unitId: 'u1', developmentName: 'Los Aromos', overallProgressPercent: 40, totalBudget: 0, totalSpent: 0,
    budgetUsedPercent: null, unassignedSpent: 0, stages, spendingCurve: [], lastExpenseOn: null, units: [], alerts: [],
    plannedCurve: [], undatedStages: 0, undatedBudget: 0, commonWorks: null, generalSpent: 0, stageOptions: [], ...overrides,
  };
}

function expense(id: string, incurredOn: string, overrides: Partial<ExpenseResponse> = {}): ExpenseResponse {
  return {
    id, developmentId: 'd1', unitId: 'u1', stageId: 's1', incurredOn, amount: 150, description: 'Hormigón',
    createdAt: incurredOn + 'T12:00:00Z', ...overrides,
  };
}

describe('Económico de la casa', () => {
  let fixture: ComponentFixture<HouseEconomicsComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(data: DashboardResponse, expenses: ExpenseResponse[] = []): void {
    TestBed.configureTestingModule({
      imports: [HouseEconomicsComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true) } },
        { provide: HouseStore, useValue: { unitId: computed(() => 'u1'), unit: computed(() => ({ code: 'Casa 1' })) } },
      ],
    });
    fixture = TestBed.createComponent(HouseEconomicsComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    flush(data, expenses);
    element = fixture.nativeElement as HTMLElement;
  }

  function flush(data: DashboardResponse, expenses: ExpenseResponse[] = []): void {
    http.expectOne(request => request.url === '/api/developments/d1/dashboard' && request.params.get('unitId') === 'u1').flush(data);
    http.expectOne('/api/developments/d1/expenses').flush(expenses);
    fixture.detectChanges();
  }

  const button = (label: string, scope: ParentNode = element) =>
    Array.from(scope.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  function type(input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  const kpis = () => Array.from(element.querySelectorAll('.kpi')).map(joined);
  const budgetInput = () => element.querySelector<HTMLInputElement>('.budget input')!;

  afterEach(() => http.verify());

  describe('indicadores', () => {
    const complete = [
      stageRow({ budgetAmount: 100_000, spentAmount: 81_400, budgetUsedPercent: 81, trafficLight: 'GREEN' }),
      stageRow({ stageId: 's2', name: 'Techo', orderIndex: 2, budgetAmount: 80_000, spentAmount: 50_000, budgetUsedPercent: 62, trafficLight: 'GREEN' }),
    ];

    it('presupuesto, gastado con su barra, costo final contra el presupuesto en ámbar y este mes contra lo previsto', () => {
      render(panel(complete, {
        totalBudget: 180_000, totalSpent: 131_400, budgetUsedPercent: 73, overallProgressPercent: 68,
        plannedCurve: [{ month, monthlyAmount: 8_200, accumulated: 120_000 }],
      }), [expense('g1', today, { amount: 9_000 }), expense('g2', today, { amount: 650 }), expense('g3', '2020-01-01')]);

      expect(kpis()[0]).toBe('Presupuesto US$ 180.000 2 etapas presupuestadas');
      expect(kpis()[1]).toBe('Gastado US$ 131.400 73% del presupuesto · avance 68%');
      expect(plain(element.querySelector('.kpi .bar')!.getAttribute('aria-label'))).toBe('73% del presupuesto gastado con 68% de avance');
      expect(kpis()[2]).toBe('Costo final estimado US$ 193.235 +US$ 13.235 sobre el presupuesto (+7%) '
        + 'Si se sigue gastando al mismo ritmo que se avanza (regla de tres).');
      expect(element.querySelectorAll('.kpi')[2].querySelector('.value')!.classList).toContain('warn');
      expect(kpis()[3]).toBe('Este mes US$ 9.650 2 gastos · se esperaban US$ 8.200');
      expect(element.querySelectorAll('.kpi')[3].querySelector('.warn')).toBeNull();
    });

    it('con el presupuesto incompleto da el estimado sin diferencia y dice por qué', () => {
      render(panel([complete[0], stageRow({ stageId: 's2', name: 'Techo', orderIndex: 2, spentAmount: 5_000 })], {
        totalBudget: 100_000, totalSpent: 86_400, budgetUsedPercent: 86, overallProgressPercent: 50,
      }));

      expect(kpis()[0]).toBe('Presupuesto US$ 100.000 1 de 2 etapas presupuestadas');
      expect(kpis()[2]).toContain('US$ 172.800 Presupuesto incompleto: 1 de 2 etapas sin presupuesto');
      expect(element.querySelectorAll('.kpi')[2].querySelector('.warn')).toBeNull();
      expect(kpis()[3]).toBe('Este mes US$ 0 Sin gastos este mes');
    });

    it('sin presupuesto ni avance no inventa: "Sin cargar" y "Sin avance todavía"', () => {
      render(panel([stageRow({ progressPercent: 0 })], { overallProgressPercent: 0 }));

      expect(kpis()[0]).toBe('Presupuesto Sin cargar Cargalo con "Editar presupuesto"');
      expect(kpis()[1]).toBe('Gastado US$ 0 Avance 0%');
      expect(kpis()[2]).toBe('Costo final estimado — Sin avance todavía');
    });
  });

  describe('curva', () => {
    it('dibuja lo previsto y lo gastado, y avisa las etapas que la prevista no incluye', () => {
      render(panel([stageRow({ budgetAmount: 1_000 })], {
        plannedCurve: [{ month: '2026-01', monthlyAmount: 500, accumulated: 500 }, { month: '2026-02', monthlyAmount: 500, accumulated: 1_000 }],
        spendingCurve: [{ month: '2026-01', monthlyAmount: 600, accumulated: 600 }],
        undatedStages: 2, undatedBudget: 21_000,
      }));
      const curve = element.querySelector('app-spending-curve')!;

      expect(curve.querySelector('path.planned')).not.toBeNull();
      expect(curve.querySelector('path.real')).not.toBeNull();
      expect(plain(curve.querySelector('svg')!.getAttribute('aria-label'))).toContain('Gastado acumulado: US$ 600');
      expect(text(curve)).toContain('La curva prevista no incluye 2 etapas sin fechas previstas (US$ 21.000).');
    });

    it('sin curva prevista, sólo lo gastado y dónde cargar las fechas', () => {
      render(panel([stageRow({ budgetAmount: 1_000 })], { spendingCurve: [{ month: '2026-01', monthlyAmount: 600, accumulated: 600 }] }));
      const curve = element.querySelector('app-spending-curve')!;

      expect(curve.querySelector('path.planned')).toBeNull();
      expect(text(curve)).not.toContain('Previsto según');
      expect(text(curve)).toContain('Sin curva prevista: cargá las fechas previstas de las etapas en Etapas.');
    });
  });

  describe('por etapa', () => {
    it('en orden de obra: presupuesto, gastado, la barra de gasto contra avance y el estado en palabras', () => {
      render(panel([
        stageRow({ stageId: 's2', name: 'Mampostería', orderIndex: 2, budgetAmount: 1_000, spentAmount: 900, budgetUsedPercent: 90, progressPercent: 30, trafficLight: 'RED' }),
        stageRow({ stageId: 's1', name: 'Fundaciones', orderIndex: 1 }),
      ]));
      const rows = Array.from(element.querySelectorAll('.stage-table tbody tr'));

      expect(Array.from(element.querySelectorAll('.stage-table thead th')).map(text))
        .toEqual(['Etapa', 'Presupuesto', 'Gastado', 'Gasto vs. avance', 'Estado']);
      expect(rows.map(row => text(row.querySelector('.name')!))).toEqual(['Fundaciones', 'Mampostería']);
      expect(text(rows[0].querySelector('.budget')!)).toBe('Cargar');
      expect(text(rows[0].querySelector('.spent')!)).toBe('—');
      expect(text(rows[0].querySelector('.chip')!)).toBe('Sin presupuesto');
      expect(text(rows[1].querySelector('.bar-text')!)).toBe('90% gastado30% avance');
      expect(text(rows[1].querySelector('.chip')!)).toBe('Gasta más de lo que avanza');
      expect(rows[1].getAttribute('data-state')).toBe('over');
    });

    it('cargar un presupuesto en la celda manda el PATCH con el monto, con Enter', () => {
      render(panel([stageRow({})]));

      button('Cargar').click();
      fixture.detectChanges();
      type(budgetInput(), '21.000');
      budgetInput().form!.dispatchEvent(new Event('submit'));

      const request = http.expectOne('/api/stages/s1/plan');
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ budgetAmount: 21000 });
      request.flush({});
      flush(panel([stageRow({ budgetAmount: 21000, trafficLight: 'GREEN', budgetUsedPercent: 0 })]));
      expect(element.querySelector('.budget input')).toBeNull();
    });

    it('vaciarlo y salir del campo manda clearBudget: queda sin presupuesto, no en cero', () => {
      render(panel([stageRow({ budgetAmount: 2000, trafficLight: 'GREEN', budgetUsedPercent: 0 })]));

      element.querySelector<HTMLButtonElement>('.budget-cell')!.click();
      fixture.detectChanges();
      expect(budgetInput().value).toBe('2.000');
      type(budgetInput(), '');
      budgetInput().dispatchEvent(new Event('blur'));

      const request = http.expectOne('/api/stages/s1/plan');
      expect(request.request.body).toEqual({ clearBudget: true });
      request.flush({});
      flush(panel([stageRow({})]));
    });

    it('con decimales se muestra en formato argentino, y si no cambia no manda nada', () => {
      render(panel([stageRow({ budgetAmount: 6300.5, trafficLight: 'GREEN', budgetUsedPercent: 0 })]));

      element.querySelector<HTMLButtonElement>('.budget-cell')!.click();
      fixture.detectChanges();
      expect(budgetInput().value).toBe('6.300,50');
      budgetInput().dispatchEvent(new Event('blur'));
      fixture.detectChanges();

      http.expectNone('/api/stages/s1/plan');
      expect(element.querySelector('.budget input')).toBeNull();
    });

    it('Escape cancela sin mandar nada; un monto inválido no se manda y lo explica', () => {
      render(panel([stageRow({})]));

      button('Cargar').click();
      fixture.detectChanges();
      type(budgetInput(), '900');
      budgetInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();
      expect(element.querySelector('.budget input')).toBeNull();

      button('Cargar').click();
      fixture.detectChanges();
      type(budgetInput(), '-5');
      budgetInput().form!.dispatchEvent(new Event('submit'));
      fixture.detectChanges();

      http.expectNone('/api/stages/s1/plan');
      expect(text(element.querySelector('app-stage-budget-table .ov-error')!)).toContain('monto válido');
    });
  });

  describe('cargar gasto', () => {
    const stages = [
      stageRow({ stageId: 's1', name: 'Fundaciones', orderIndex: 1, status: 'COMPLETED' }),
      stageRow({ stageId: 's2', name: 'Instalación eléctrica', orderIndex: 2, status: 'IN_PROGRESS' }),
    ];

    it('abre la franja con la etapa en curso, sin "Dónde", y queda abierta para el siguiente', () => {
      render(panel(stages));

      button('Cargar gasto').click();
      fixture.detectChanges();
      const strip = element.querySelector('app-expense-strip')!;
      const stage = strip.querySelector<HTMLSelectElement>('#strip-stage')!;
      expect(strip.querySelector('#strip-place')).toBeNull();
      expect(Array.from(stage.options).map(option => option.text)).toEqual(['Fundaciones', 'Instalación eléctrica', 'General de la casa']);
      expect(stage.value).toBe('s2');
      expect(strip.querySelector<HTMLInputElement>('#strip-date')!.value).toBe(today);

      type(strip.querySelector('#strip-amount')!, '4.800');
      type(strip.querySelector('#strip-concept')!, 'Cables y tablero');
      button('Guardar', strip).click();

      const request = http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/expenses');
      expect(request.request.body).toEqual({ amount: 4800, stageId: 's2', unitId: 'u1', incurredOn: today, description: 'Cables y tablero' });
      request.flush(expense('g9', today, { amount: 4800, stageId: 's2' }));
      flush(panel(stages), [expense('g9', today, { amount: 4800, stageId: 's2' })]);

      const after = element.querySelector('app-expense-strip')!;
      expect(text(after.querySelector('.saved')!)).toBe('Cargado: US$ 4.800 en Instalación eléctrica. Podés cargar el siguiente.');
      expect(after.querySelector<HTMLInputElement>('#strip-amount')!.value).toBe('');
      button('Cerrar', after).click();
      fixture.detectChanges();
      expect(element.querySelector('app-expense-strip')).toBeNull();
    });

    it('un monto que no es mayor que cero no se manda y lo explica; "General de la casa" va sin etapa', () => {
      render(panel(stages));

      button('Cargar gasto').click();
      fixture.detectChanges();
      const strip = element.querySelector('app-expense-strip')!;
      type(strip.querySelector('#strip-amount')!, '0');
      button('Guardar', strip).click();
      fixture.detectChanges();
      http.expectNone(req => req.method === 'POST');
      expect(text(strip.querySelector('[role=alert]')!)).toContain('mayor que cero');

      type(strip.querySelector('#strip-amount')!, '180');
      type(strip.querySelector('#strip-stage')!, '');
      button('Guardar', strip).click();
      const request = http.expectOne(req => req.method === 'POST');
      expect(request.request.body).toEqual(expect.objectContaining({ amount: 180, stageId: null, unitId: 'u1' }));
      request.flush(expense('g1', today, { stageId: null }));
      flush(panel(stages));
      expect(text(strip.querySelector('.saved')!)).toContain('en General de la casa');
    });
  });

  describe('últimos gastos', () => {
    const many = Array.from({ length: 7 }, (_, i) => expense('g' + i, `2026-09-0${i + 1}`));

    it('los cinco más nuevos con fecha, concepto, etapa y monto, y "Ver todos (N)"', () => {
      render(panel([stageRow({})]), [...many, expense('gx', '2026-09-20', { stageId: null, description: null, amount: 1250.5 })]);
      const rows = () => Array.from(element.querySelectorAll('.expense-table tbody tr'));

      expect(text(element.querySelector('#expenses-title')!)).toBe('Últimos gastos');
      expect(Array.from(element.querySelectorAll('.expense-table thead th')).map(text).slice(0, 4))
        .toEqual(['Fecha', 'Concepto', 'Etapa', 'Monto']);
      expect(rows()).toHaveLength(5);
      expect(text(rows()[0].querySelector('.stage')!)).toBe('General de la casa');
      expect(text(rows()[0].querySelector('.concept')!)).toBe('Sin detalle');
      expect(text(rows()[0].querySelector('.amount')!)).toBe('US$ 1.250,50');
      expect(button('Cargar gasto', element.querySelector('app-expenses-panel')!)).toBeUndefined();

      button('Ver todos (8)').click();
      fixture.detectChanges();
      expect(rows()).toHaveLength(8);
      expect(button('Ver todos (8)')).toBeUndefined();
    });

    it('sólo los de la casa; "Editar" corrige con PATCH y "Borrar" pide confirmación', () => {
      render(panel([stageRow({})]), [expense('g1', '2026-09-01', { amount: 6300.5 }), expense('g2', '2026-09-02', { unitId: 'u2' })]);

      expect(element.querySelectorAll('.expense-table tbody tr')).toHaveLength(1);
      button('Editar').click();
      fixture.detectChanges();
      const editing = element.querySelector('tr.editing')!;
      expect(editing.querySelector<HTMLInputElement>('#expense-amount')!.value).toBe('6.300,50');
      button('Guardar', editing).click();
      const patch = http.expectOne('/api/expenses/g1');
      expect(patch.request.method).toBe('PATCH');
      patch.flush(expense('g1', '2026-09-01'));
      flush(panel([stageRow({})]), [expense('g1', '2026-09-01')]);

      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      button('Borrar').click();
      http.expectNone('/api/expenses/g1');
      confirm.mockReturnValue(true);
      button('Borrar').click();
      http.expectOne(req => req.method === 'DELETE' && req.url === '/api/expenses/g1').flush(null);
      flush(panel([stageRow({})]), []);
      confirm.mockRestore();
      expect(text(element.querySelector('app-expenses-panel')!)).toContain('Todavía no hay gastos cargados');
    });
  });

  describe('editar presupuesto', () => {
    const stages = [
      stageRow({ stageId: 's1', name: 'Fundaciones', orderIndex: 1, budgetAmount: 16_000 }),
      stageRow({ stageId: 's2', name: 'Estructura', orderIndex: 2, budgetAmount: 30_000 }),
      stageRow({ stageId: 's3', name: 'Techo', orderIndex: 3 }),
    ];

    function open(): Element {
      button('Editar presupuesto').click();
      fixture.detectChanges();
      return element.querySelector('app-budget-paste')!;
    }

    it('pegado desde Excel: muestra qué queda por etapa y guarda una tras otra sólo las que cambian', () => {
      render(panel(stages));
      const dialog = open();

      type(dialog.querySelector('textarea')!, '17.000\r\n\r\nUS$ 12.000');
      const rows = Array.from(dialog.querySelectorAll('tbody tr')).map(joined);
      expect(rows).toEqual(['Fundaciones US$ 16.000 US$ 17.000', 'Estructura US$ 30.000 Sin cambio', 'Techo — US$ 12.000']);
      expect(dialog.querySelector('[role=status]')).toBeNull();

      button('Guardar 2 etapas', dialog).click();
      const first = http.expectOne('/api/stages/s1/plan');
      expect(first.request.body).toEqual({ budgetAmount: 17000 });
      http.expectNone('/api/stages/s3/plan');
      first.flush({});
      http.expectOne('/api/stages/s3/plan').flush({});
      flush(panel(stages));
      fixture.detectChanges();
      expect(element.querySelector('app-budget-paste')).toBeNull();
    });

    it('un número que no se entiende frena todo, y avisa si las líneas no son tantas como las etapas', () => {
      render(panel(stages));
      const dialog = open();

      type(dialog.querySelector('textarea')!, '12,000.50\n9.000');
      expect(text(dialog.querySelector('tbody tr')!)).toContain('"12,000.50": No es un monto');
      expect(text(dialog.querySelector('[role=status]')!)).toBe('Pegaste 2 líneas para 3 etapas: la última queda como está.');
      expect(button('Guardar 1 etapa', dialog).disabled).toBe(true);
    });

    it('si una etapa falla, dice cuál y por qué; las demás quedan guardadas', () => {
      render(panel(stages));
      const dialog = open();

      type(dialog.querySelector('textarea')!, '17.000\n31.000');
      button('Guardar 2 etapas', dialog).click();
      http.expectOne('/api/stages/s1/plan').flush({ message: 'La etapa ya no existe.' }, { status: 404, statusText: 'Not Found' });
      http.expectOne('/api/stages/s2/plan').flush({});
      flush(panel(stages));

      expect(joined(element.querySelector('app-budget-paste [role=alert]')!))
        .toBe('No se pudo guardar una etapa; las demás quedaron guardadas: Fundaciones: La etapa ya no existe.');
    });
  });
});
