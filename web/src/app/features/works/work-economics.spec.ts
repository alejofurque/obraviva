import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { DashboardResponse, ExpenseResponse, StageDashboardRow, StageOption, UnitDashboardRow } from '../../core/api/api.models';
import { todayLocal } from '../../shared/time/local-date';
import { aDevelopment } from '../../testing/fixtures';
import { formatAmount, parseAmount } from './amounts';
import { formatMoney } from '../../shared/money/money';
import { monthlyBars } from './economics-derivations';
import { WorkEconomicsComponent } from './work-economics.component';
import { WorkStore } from './work.store';

registerLocaleData(localeEsAr);

const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
/** El texto de cada hijo separado por un espacio: Angular no deja espacios entre elementos. */
const joined = (element: Element) => Array.from(element.children).map(text).filter(Boolean).join(' ');
const month = todayLocal().slice(0, 7);

function monthsBefore(count: number): string {
  const [year, number] = month.split('-').map(Number);
  const date = new Date(year, number - 1 - count, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function stageRow(overrides: Partial<StageDashboardRow>): StageDashboardRow {
  return {
    stageId: 'c1', name: 'Calles', orderIndex: 1, status: 'IN_PROGRESS', progressPercent: 40, weight: 1,
    budgetAmount: null, spentAmount: 0, budgetUsedPercent: null, trafficLight: 'NO_BUDGET', ...overrides,
  };
}

function unitRow(overrides: Partial<UnitDashboardRow>): UnitDashboardRow {
  return {
    unitId: 'u1', code: 'Casa 1', progressPercent: 68, budgetAmount: 180_000, spentAmount: 131_400, budgetUsedPercent: 73,
    trafficLight: 'GREEN', worstStageLight: 'GREEN', worstStageName: null, stagesWithoutBudget: 0, stageCount: 12, ...overrides,
  };
}

const option = (stageId: string, unitId: string | null, name: string, orderIndex: number): StageOption =>
  ({ stageId, unitId, name, orderIndex });

function panel(overrides: Partial<DashboardResponse> = {}): DashboardResponse {
  return {
    developmentId: 'd1', unitId: null, developmentName: 'Los Aromos', overallProgressPercent: 34, totalBudget: 500_000,
    totalSpent: 224_800, budgetUsedPercent: 45, unassignedSpent: 1_000, stages: [stageRow({ budgetAmount: 140_000 })],
    spendingCurve: [], lastExpenseOn: null, alerts: [], plannedCurve: [], undatedStages: 0, undatedBudget: 0,
    units: [
      unitRow({}),
      unitRow({ unitId: 'u2', code: 'Casa 2', progressPercent: 0, spentAmount: 0, budgetUsedPercent: 0 }),
    ],
    commonWorks: unitRow({
      unitId: null, code: null, budgetAmount: 140_000, spentAmount: 92_400, progressPercent: 66, budgetUsedPercent: 66,
      worstStageLight: 'YELLOW', worstStageName: 'Calles', stageCount: 1,
    }),
    generalSpent: 1_000,
    stageOptions: [
      option('s1', 'u1', 'Trabajos preliminares', 1), option('s2', 'u1', 'Estructura', 2),
      option('s3', 'u2', 'Trabajos preliminares', 1), option('c1', null, 'Calles', 1),
    ],
    ...overrides,
  };
}

function expense(id: string, unitId: string | null, stageId: string | null, overrides: Partial<ExpenseResponse> = {}): ExpenseResponse {
  return {
    id, developmentId: 'd1', unitId, stageId, incurredOn: '2026-09-10', amount: 150, description: 'Gasto',
    createdAt: '2026-09-10T12:00:00Z', ...overrides,
  };
}

describe('Montos en formato argentino', () => {
  it('lee punto de miles y coma decimal', () => {
    expect(parseAmount('6.300.000')).toBe(6300000);
    expect(parseAmount('6.300.000,50')).toBe(6300000.5);
    expect(parseAmount('1500')).toBe(1500);
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('6.3')).toBe('invalid');
    expect(parseAmount('-3')).toBe('invalid');
  });

  it('los muestra igual al editar, y lo que se muestra se vuelve a leer igual', () => {
    expect(formatAmount(6300000)).toBe('6.300.000');
    expect(formatAmount(6300000.5)).toBe('6.300.000,50');
    expect(formatAmount(1500)).toBe('1.500');
    for (const value of [6300000, 6300000.5, 1500]) {
      expect(parseAmount(formatAmount(value))).toBe(value);
    }
  });

  it('en pantalla, los centavos van siempre con dos dígitos y los enteros sin decimales', () => {
    expect(formatMoney(6300000)).toBe('US$ 6.300.000');
    expect(formatMoney(6300000.5)).toBe('US$ 6.300.000,50');
  });
});

describe('Gasto por mes', () => {
  it('llena los meses sin gastos, marca el actual y promedia sólo los cerrados', () => {
    const { bars, average } = monthlyBars([
      { month: '2026-07', monthlyAmount: 3_000, accumulated: 3_000 },
      { month: '2026-09', monthlyAmount: 500, accumulated: 3_500 },
    ], '2026-09');

    expect(bars.map(bar => [bar.label, bar.amount, bar.current])).toEqual([['jul', 3000, false], ['ago', 0, false], ['sep', 500, true]]);
    expect(bars[0].height).toBe(100);
    expect(average).toEqual({ months: 2, amount: 1500 });
  });
});

describe('Económico del desarrollo', () => {
  let fixture: ComponentFixture<WorkEconomicsComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;

  function render(data: DashboardResponse, expenses: ExpenseResponse[] = []): void {
    TestBed.configureTestingModule({
      imports: [WorkEconomicsComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'es-AR' },
        { provide: WorkStore, useValue: { development: signal(aDevelopment()).asReadonly(), isOwner: computed(() => true) } },
      ],
    });
    fixture = TestBed.createComponent(WorkEconomicsComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    flush(data, expenses);
    element = fixture.nativeElement as HTMLElement;
  }

  function flush(data: DashboardResponse, expenses: ExpenseResponse[] = []): void {
    http.expectOne(request => request.url === '/api/developments/d1/dashboard' && !request.params.has('unitId')).flush(data);
    http.expectOne('/api/developments/d1/expenses').flush(expenses);
    fixture.detectChanges();
  }

  const button = (label: string, scope: ParentNode = element) =>
    Array.from(scope.querySelectorAll<HTMLButtonElement>('button')).find(item => text(item) === label)!;

  function type(input: HTMLInputElement | HTMLSelectElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  const kpis = () => Array.from(element.querySelectorAll('.kpi')).map(joined);

  afterEach(() => http.verify());

  describe('indicadores', () => {
    it('presupuesto con las obras comunes, gastado, el estimado como suma de las partes y lo que hay para mirar', () => {
      render(panel({
        alerts: [
          { stageId: 's2', name: 'Estructura', unitId: 'u1', unitCode: 'Casa 1', trafficLight: 'RED', budgetUsedPercent: 80, progressPercent: 20 },
          { stageId: 'c1', name: 'Calles', unitId: null, unitCode: null, trafficLight: 'YELLOW', budgetUsedPercent: 60, progressPercent: 40 },
        ],
      }));

      expect(kpis()[0]).toBe('Presupuesto total US$ 500.000 2 casas · obras comunes US$ 140.000');
      expect(kpis()[1]).toBe('Gastado US$ 224.800 45% del presupuesto · avance 34%');
      // Casa 1 por regla de tres (131.400 / 0,68), Casa 2 sin avance con su presupuesto,
      // las comunes por regla de tres (92.400 / 0,66) y lo general tal cual.
      expect(kpis()[2]).toBe('Costo final estimado US$ 514.235 +US$ 14.235 sobre el presupuesto (+3%) '
        + 'Si se sigue gastando al mismo ritmo que se avanza (regla de tres).');
      expect(kpis()[3]).toBe('Para mirar 2 etapas Gastan más rápido de lo que avanzan');
      expect(element.querySelectorAll('.kpi')[3].querySelector('.value')!.classList).toContain('warn');
    });

    it('con una casa sin presupuesto completo, el estimado va sin diferencia y el gastado sin porcentaje', () => {
      render(panel({ units: [unitRow({ stagesWithoutBudget: 3 })] }));

      expect(kpis()[1]).toBe('Gastado US$ 224.800 Avance 34%');
      expect(kpis()[2]).toContain('Presupuesto incompleto: 1 parte tiene etapas sin presupuesto');
      expect(kpis()[3]).toBe('Para mirar Nada Ninguna etapa gasta más de lo que avanza');
      // Que sus etapas presupuestadas vayan bien no dice que la casa vaya bien.
      const row = element.querySelector('.house-table tbody tr')!;
      expect(joined(row.querySelector('.estimate')!)).toBe('US$ 193.235 Sin comparar');
      expect(joined(row.querySelector('.state')!)).toBe('Presupuesto incompleto 3 de 12 etapas sin presupuesto');
    });
  });

  describe('por casa', () => {
    it('cada casa y las obras comunes con su estimado, su diferencia y el estado de su peor etapa', () => {
      render(panel());
      const rows = Array.from(element.querySelectorAll('.house-table tbody tr'));

      expect(rows.map(row => text(row.querySelector('.name')!))).toEqual(['Casa 1', 'Casa 2', 'Obras comunes']);
      expect(joined(rows[0].querySelector('.estimate')!)).toBe('US$ 193.235 +US$ 13.235 (+7%)');
      expect(rows[0].querySelector('.difference')!.classList).toContain('warn');
      expect(text(rows[0].querySelector('.bar-text')!)).toBe('73% gastado68% avance');
      expect(joined(rows[1].querySelector('.estimate')!)).toBe('US$ 180.000 Sin avance aún');
      expect(text(rows[1].querySelector('.spent')!)).toBe('—');
      expect(text(rows[1].querySelector('.chip')!)).toBe('Sin empezar');
      expect(joined(rows[2].querySelector('.state')!)).toBe('Atención Calles');
      expect(rows[2].getAttribute('data-state')).toBe('watch');
    });

    it('una casa lleva a su Económico, por el enlace o tocando la fila', () => {
      render(panel());
      const rows = Array.from(element.querySelectorAll<HTMLElement>('.house-table tbody tr'));

      expect(rows[0].querySelector('a')!.getAttribute('href')).toBe('/obras/d1/casas/u1/economico');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      rows[1].querySelector<HTMLElement>('.chip')!.click();
      expect(navigate).toHaveBeenCalledWith(['/obras', 'd1', 'casas', 'u2', 'economico']);
    });
  });

  it('gasto por mes: el mes en curso en gris y el promedio de los cerrados', () => {
    render(panel({
      spendingCurve: [
        { month: monthsBefore(2), monthlyAmount: 3_000, accumulated: 3_000 },
        { month, monthlyAmount: 500, accumulated: 3_500 },
      ],
    }));
    const monthly = element.querySelector('app-monthly-spending')!;

    expect(monthly.querySelectorAll('.month')).toHaveLength(3);
    expect(monthly.querySelectorAll('.month')[2].classList).toContain('current');
    expect(text(monthly.querySelector('p.muted')!)).toBe('Promedio de los últimos 2 meses: US$ 1.500 por mes. El mes en curso va en gris.');
  });

  describe('últimos gastos', () => {
    it('dicen dónde va cada uno: la casa con su etapa, las obras comunes o general del desarrollo', () => {
      render(panel(), [
        expense('e1', 'u1', 's2', { incurredOn: '2026-09-14' }),
        expense('e2', null, 'c1', { incurredOn: '2026-09-13' }),
        expense('e3', null, null, { incurredOn: '2026-09-12' }),
        expense('e4', 'u1', null, { incurredOn: '2026-09-11' }),
      ]);
      const rows = Array.from(element.querySelectorAll('.expense-table tbody tr'));

      expect(text(element.querySelectorAll('.expense-table thead th')[2])).toBe('Dónde');
      expect(rows.map(row => text(row.querySelector('.stage')!)))
        .toEqual(['Casa 1 · Estructura', 'Obras comunes · Calles', 'General del desarrollo', 'Casa 1 · General']);
    });

    it('al corregir uno de una casa ofrece las etapas de esa casa', () => {
      render(panel(), [expense('e1', 'u1', 's2')]);

      button('Editar').click();
      fixture.detectChanges();
      const stage = element.querySelector<HTMLSelectElement>('tr.editing #expense-stage')!;
      expect(Array.from(stage.options).map(item => item.text)).toEqual(['General de la casa', 'Trabajos preliminares', 'Estructura']);
      expect(stage.value).toBe('s2');
    });
  });

  describe('cargar gasto', () => {
    function openStrip(): Element {
      button('Cargar gasto').click();
      fixture.detectChanges();
      return element.querySelector('app-expense-strip')!;
    }

    it('"Dónde" elige la casa, las obras comunes o general del desarrollo, y la etapa sigue al lugar', () => {
      render(panel());
      const strip = openStrip();
      const place = strip.querySelector<HTMLSelectElement>('#strip-place')!;
      const stages = () => Array.from(strip.querySelectorAll<HTMLOptionElement>('#strip-stage option')).map(item => item.text);

      expect(Array.from(place.options).map(item => item.text)).toEqual(['Casa 1', 'Casa 2', 'Obras comunes', 'General del desarrollo']);
      expect(stages()).toEqual(['Trabajos preliminares', 'Estructura', 'General de la casa']);
      // Sin una etapa propuesta, va "General de la casa" y no la primera etapa a ciegas.
      expect(strip.querySelector<HTMLSelectElement>('#strip-stage')!.value).toBe('');

      type(place, 'comunes');
      expect(stages()).toEqual(['Calles']);
      type(place, 'general');
      expect(strip.querySelector('#strip-stage')).toBeNull();

      type(strip.querySelector('#strip-amount')!, '7.200');
      button('Guardar', strip).click();
      const request = http.expectOne(req => req.method === 'POST' && req.url === '/api/developments/d1/expenses');
      expect(request.request.body).toEqual(expect.objectContaining({ amount: 7200, unitId: null, stageId: null }));
      request.flush(expense('e9', null, null));
      flush(panel(), [expense('e9', null, null)]);
      expect(text(strip.querySelector('.saved')!)).toBe('Cargado: US$ 7.200 en General del desarrollo. Podés cargar el siguiente.');
    });

    it('un gasto de las obras comunes va con su etapa y sin casa', () => {
      render(panel());
      const strip = openStrip();

      type(strip.querySelector('#strip-place')!, 'comunes');
      type(strip.querySelector('#strip-amount')!, '300');
      button('Guardar', strip).click();
      const request = http.expectOne(req => req.method === 'POST');
      expect(request.request.body).toEqual(expect.objectContaining({ amount: 300, unitId: null, stageId: 'c1' }));
      request.flush(expense('e9', null, 'c1'));
      flush(panel());
    });
  });

  it('las obras comunes, por etapa, con el presupuesto editable en la celda', () => {
    render(panel({ stages: [stageRow({})] }));

    button('Cargar').click();
    fixture.detectChanges();
    const input = element.querySelector<HTMLInputElement>('.budget input')!;
    type(input, '80.000');
    input.form!.dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/stages/c1/plan');
    expect(request.request.body).toEqual({ budgetAmount: 80000 });
    request.flush({});
    flush(panel());
  });

  it('en orden: indicadores, por casa, gasto por mes y últimos gastos, y las obras comunes al final', () => {
    render(panel());
    const headings = Array.from(element.querySelectorAll('h3')).map(text);

    expect(headings).toEqual(['Por casa', 'Gasto por mes', 'Últimos gastos', 'Obras comunes, por etapa']);
    expect(element.querySelector('header a')!.getAttribute('href')).toBe('/obras/d1/economico/reportes');
  });
});
