import { fadeSides } from './scroll-fade.directive';

describe('Pestañas cortadas', () => {
  it('se difuminan del lado donde hay más para ver', () => {
    expect(fadeSides(0, 358, 358)).toEqual({ start: false, end: false });
    expect(fadeSides(0, 358, 700)).toEqual({ start: false, end: true });
    expect(fadeSides(120, 358, 700)).toEqual({ start: true, end: true });
    expect(fadeSides(342, 358, 700)).toEqual({ start: true, end: false });
  });

  it('medio píxel de redondeo no cuenta como "hay más"', () => {
    expect(fadeSides(0.5, 358, 358.5)).toEqual({ start: false, end: false });
  });
});
