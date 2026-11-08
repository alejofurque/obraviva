import { TestBed } from '@angular/core/testing';
import { StageStatus } from '../../core/api/api.models';
import { StatusBadgeComponent } from './status-badge.component';

describe('StatusBadgeComponent', () => {
  function render(status: StageStatus): HTMLElement {
    TestBed.configureTestingModule({ imports: [StatusBadgeComponent] });
    const fixture = TestBed.createComponent(StatusBadgeComponent);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const cases: [StageStatus, string][] = [
    ['PENDING', 'Por empezar'],
    ['IN_PROGRESS', 'En curso'],
    ['COMPLETED', 'Terminada'],
    ['DELAYED', 'Demorada'],
  ];

  it.each(cases)('muestra %s con su nombre escrito, no sólo con color', (status, label) => {
    const element = render(status);

    expect(element.textContent?.trim()).toBe(label);
    expect(element.getAttribute('data-status')).toBe(status);
  });
});
