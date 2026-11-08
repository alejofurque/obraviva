import { DestroyRef, Directive, ElementRef, afterNextRender, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** De qué lados hay más para ver en una fila que se desplaza de costado. */
export function fadeSides(scrollLeft: number, clientWidth: number, scrollWidth: number): { start: boolean; end: boolean } {
  // Un píxel de margen: el redondeo del navegador deja medio píxel de "desplazamiento" sin contenido.
  return { start: scrollLeft > 1, end: scrollLeft + clientWidth < scrollWidth - 1 };
}

/**
 * Una fila de pestañas que no entra en la pantalla avisa que hay más: se difumina del lado que
 * sigue (`.fade-start`, `.fade-end`, en `.ov-tabs`) y, al cambiar de pantalla, se desplaza hasta
 * la pestaña activa para que no quede tapada. Sin animaciones atadas al desplazamiento: Safari
 * del teléfono no las tiene.
 */
@Directive({
  selector: '[appScrollFade]',
  host: { '(scroll)': 'update()' },
})
export class ScrollFadeDirective {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    afterNextRender(() => {
      this.showActive();
      if (typeof ResizeObserver === 'function') {
        const observer = new ResizeObserver(() => this.update());
        observer.observe(this.element);
        this.destroyRef.onDestroy(() => observer.disconnect());
      }
    });
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), takeUntilDestroyed(this.destroyRef))
      // `routerLinkActive` marca la pestaña después del evento: se espera un cuadro.
      .subscribe(() => requestAnimationFrame(() => this.showActive()));
  }

  protected update(): void {
    const sides = fadeSides(this.element.scrollLeft, this.element.clientWidth, this.element.scrollWidth);
    this.element.classList.toggle('fade-start', sides.start);
    this.element.classList.toggle('fade-end', sides.end);
  }

  private showActive(): void {
    const active = this.element.querySelector<HTMLElement>('.active');
    if (active && this.element.scrollWidth > this.element.clientWidth) {
      const view = this.element.scrollLeft;
      const left = active.getBoundingClientRect().left - this.element.getBoundingClientRect().left + view;
      const right = left + active.offsetWidth;
      if (left < view || right > view + this.element.clientWidth) {
        this.element.scrollLeft = Math.max(0, left - this.element.clientWidth / 3);
      }
    }
    this.update();
  }
}
