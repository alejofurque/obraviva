import { todayLocal } from './local-date';

describe('todayLocal', () => {
  it('usa los componentes locales, no UTC', () => {
    // 23:30 del 12 de septiembre en hora local: en UTC-3 ya sería 13 en UTC.
    expect(todayLocal(new Date(2026, 8, 12, 23, 30))).toBe('2026-09-12');
    expect(todayLocal(new Date(2026, 0, 5, 0, 5))).toBe('2026-01-05');
  });
});
