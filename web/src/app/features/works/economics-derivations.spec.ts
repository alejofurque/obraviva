import {
  curveChart, developmentEstimate, differenceLine, finalCost, houseEstimate, monthAmount, parsePastedAmounts, spendState,
} from './economics-derivations';

const money = (value: number) => `US$ ${Math.round(value).toLocaleString('es-AR')}`;

describe('Económico: estimados', () => {
  it('el costo final es lo gastado dividido por el avance; sin avance no hay, al 100% es lo gastado', () => {
    expect(finalCost(131_400, 68)).toBeCloseTo(193_235, 0);
    expect(finalCost(5_000, 0)).toBeNull();
    expect(finalCost(176_500, 100)).toBe(176_500);
  });

  it('la casa se compara con su presupuesto sólo si todas sus etapas lo tienen', () => {
    const complete = houseEstimate(180_000, 131_400, 68, 0, 12);
    expect(differenceLine(complete, money)).toBe('+US$ 13.235 sobre el presupuesto (+7%)');
    const incomplete = houseEstimate(12_000, 300_000, 65, 11, 12);
    expect(incomplete.value).toBeCloseTo(461_538, 0);
    expect(incomplete.difference).toBeNull();
    expect(incomplete.incomplete).toBe('Presupuesto incompleto: 11 de 12 etapas sin presupuesto');
    expect(houseEstimate(150_000, 0, 0, 0, 12).value).toBeNull();
  });

  it('el desarrollo suma sus partes; una sin avance cuenta con su presupuesto', () => {
    const estimate = developmentEstimate([
      { budget: 210_000, spent: 144_081, progressPercent: 68, stagesWithoutBudget: 0 },
      { budget: 150_000, spent: 0, progressPercent: 0, stagesWithoutBudget: 0 },
      { budget: 368_000, spent: 205_700, progressPercent: 69, stagesWithoutBudget: 0 },
    ]);
    expect(estimate.value).toBeCloseTo(144_081 / 0.68 + 150_000 + 205_700 / 0.69, 0);
    expect(estimate.difference).toBeCloseTo(estimate.value! - 728_000, 0);
    const incomplete = developmentEstimate([{ budget: 12_000, spent: 300_000, progressPercent: 65, stagesWithoutBudget: 11 }]);
    expect(incomplete.difference).toBeNull();
    expect(incomplete.incomplete).toContain('1 parte tiene etapas sin presupuesto');
  });

  it('el estado de una etapa en palabras: alertas en ámbar, sin presupuesto o sin gastos en gris', () => {
    expect(spendState('GREEN', 100)).toEqual({ state: 'ok', label: 'En línea' });
    expect(spendState('YELLOW', 100).label).toBe('Atención');
    expect(spendState('RED', 100).label).toBe('Gasta más de lo que avanza');
    expect(spendState('NO_BUDGET', 100).label).toBe('Sin presupuesto');
    expect(spendState('GREEN', 0).label).toBe('Sin gastos');
  });
});

describe('Económico: la curva', () => {
  it('pone las dos curvas sobre los mismos meses, sin saltos; lo gastado llega hasta el mes actual', () => {
    const chart = curveChart(
      [{ month: '2026-08', monthlyAmount: 10, accumulated: 10 }, { month: '2026-09', monthlyAmount: 20, accumulated: 30 },
        { month: '2026-11', monthlyAmount: 30, accumulated: 60 }],
      [{ month: '2026-08', monthlyAmount: 12, accumulated: 12 }, { month: '2026-10', monthlyAmount: 5, accumulated: 17 }],
      '2026-10',
    );
    expect(chart.months).toEqual(['2026-08', '2026-09', '2026-10', '2026-11']);
    expect(chart.planned).toEqual([10, 30, 30, 60]);
    expect(chart.real).toEqual([12, 12, 17, null]);
    expect(chart.max).toBe(60);
    expect(monthAmount([{ month: '2026-10', monthlyAmount: 5, accumulated: 17 }], '2026-10')).toBe(5);
    expect(monthAmount([], '2026-10')).toBe(0);
  });

  it('sin curva prevista, sólo lo gastado', () => {
    const chart = curveChart([], [{ month: '2026-01', monthlyAmount: 9, accumulated: 9 }], '2026-03');
    expect(chart.planned).toBeNull();
    expect(chart.months).toEqual(['2026-01', '2026-02', '2026-03']);
    expect(chart.real).toEqual([9, 9, 9]);
  });
});

describe('Económico: pegar desde Excel', () => {
  it('lee una columna como en Argentina, con o sin el símbolo de moneda, y las líneas vacías no cambian nada', () => {
    const pasted = parsePastedAmounts('12.000\r\n$ 21.000,50\nUS$ 7.300\n\n1.234.567\n');
    expect(pasted.map(item => item.amount)).toEqual([12000, 21000.5, 7300, null, 1234567]);
    expect(pasted.every(item => item.error === null)).toBe(true);
  });

  it('un número a la inglesa o varias columnas son un error, no un monto mal leído', () => {
    const pasted = parsePastedAmounts('12,000.50\n1.5\n12000\t500\n-300');
    expect(pasted.map(item => item.error !== null)).toEqual([true, true, true, true]);
    expect(pasted[2].error).toBe('Pegá una sola columna');
  });
});
