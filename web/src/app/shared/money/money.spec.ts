import { CURRENCY, compactMoney, formatMoney, formatWholeMoney } from './money';
import { MoneyPipe } from './money.pipe';

const NBSP = ' ';

describe('Moneda del sistema', () => {
  it('es una sola: dólares', () => {
    expect(CURRENCY).toBe('USD');
  });

  it('los montos se escriben en US$, como en Argentina; los centavos, con dos dígitos', () => {
    expect(formatMoney(180000)).toBe(`US$${NBSP}180.000`);
    expect(formatMoney(180000.5)).toBe(`US$${NBSP}180.000,50`);
    expect(formatWholeMoney(193199.6)).toBe(`US$${NBSP}193.200`);
  });

  it('en un indicador chico, en mil o en millones', () => {
    expect(compactMoney(41_200_000)).toBe(`US$${NBSP}41,2 M`);
    expect(compactMoney(850_400)).toBe(`US$${NBSP}850 mil`);
    expect(compactMoney(900)).toBe(`US$${NBSP}900`);
    expect(compactMoney(-13_200)).toBe(`-US$${NBSP}13 mil`);
  });

  it('el pipe: sin monto, un guion', () => {
    const pipe = new MoneyPipe();
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(4800)).toBe(`US$${NBSP}4.800`);
    expect(pipe.transform(4800.4, 'whole')).toBe(`US$${NBSP}4.800`);
  });
});
