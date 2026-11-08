import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * Las tres tarjetas del panel de ingreso: cómo se ve una obra en
 * ObraViva, dibujado en HTML y CSS, sin imágenes. Son decorativas: van ocultas para el lector de
 * pantalla y no muestran datos reales. En el teléfono quedan dos, en chico.
 */
@Component({
  selector: 'app-login-showcase',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `
    <div class="card progress">
      <div class="ring"><div>68%</div></div>
      <div>
        <div class="title">Tu casa avanza.</div>
        <div class="chip">En tiempo · Nov 2027</div>
      </div>
    </div>

    <div class="card tour">
      <div class="row"><strong>Recorrido 3D · Living</strong><span class="faint">Oct 2026</span></div>
      <div class="scene"><mat-icon svgIcon="view_in_ar" /></div>
      <div class="months">
        <div class="dots"><i></i><i></i><i></i><i class="now"></i></div>
        <div class="row faint"><span>Jul</span><span>Ago</span><span>Sep</span><strong>Oct</strong></div>
      </div>
    </div>

    <div class="card news">
      <div class="who">
        <div class="avatar">CM</div>
        <div><strong>Carlos publicó un avance</strong><div class="muted">Casa 1 · hace 2 horas</div></div>
      </div>
      <div>Se terminó el cableado de la planta alta.</div>
      <div class="change">Instalación eléctrica 35% → 50%</div>
    </div>
  `,
  styles: `
    :host {
      position: relative;
      display: block;
    }

    .card {
      position: absolute;
      display: flex;
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      color: var(--ov-ink);
      box-shadow: 0 16px 48px color-mix(in srgb, var(--ov-ink) 24%, transparent);
    }

    .progress {
      top: 4rem;
      left: 3.5rem;
      width: 20.5rem;
      align-items: center;
      gap: var(--ov-space-4);
      padding: var(--ov-space-5);
    }

    .ring,
    .ring > div,
    .scene,
    .avatar {
      display: grid;
      place-items: center;
      border-radius: 50%;
    }

    .ring {
      flex: 0 0 4.5rem;
      height: 4.5rem;
      background: conic-gradient(var(--ov-accent) 0 68%, var(--ov-surface-muted) 68% 100%);
    }

    .ring > div {
      width: 3.5rem;
      height: 3.5rem;
      background: var(--ov-surface);
      font-weight: var(--ov-weight-semibold);
    }

    .title {
      font-family: var(--ov-font-display);
      font-size: var(--ov-text-lg);
      line-height: 1.1;
    }

    .chip,
    .change {
      display: inline-block;
      margin-top: var(--ov-space-2);
      padding: 0.2rem var(--ov-space-2);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .tour {
      top: 13rem;
      right: 3rem;
      width: 20rem;
      flex-direction: column;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .row {
      display: flex;
      justify-content: space-between;
      font-size: var(--ov-text-xs);
    }

    .faint span,
    span.faint {
      color: color-mix(in srgb, var(--ov-on-ink) 70%, transparent);
    }

    .scene {
      height: 6rem;
      border-radius: var(--ov-radius-md);
      background: radial-gradient(circle at 30% 35%,
        color-mix(in srgb, var(--ov-surface) 52%, var(--ov-ink)), color-mix(in srgb, var(--ov-surface) 18%, var(--ov-ink)) 70%);
    }

    .scene mat-icon {
      width: 2.25rem;
      height: 2.25rem;
      color: var(--ov-on-ink);
      opacity: 0.85;
    }

    .dots {
      display: flex;
      justify-content: space-between;
      align-items: center;
      height: 0.875rem;
      margin-bottom: var(--ov-space-2);
      background: linear-gradient(color-mix(in srgb, var(--ov-accent) 45%, var(--ov-surface)) 0 0) center / 100% 2px no-repeat;
    }

    .dots i {
      width: 0.625rem;
      height: 0.625rem;
      border-radius: 50%;
      background: color-mix(in srgb, var(--ov-accent) 45%, var(--ov-surface));
    }

    .dots i.now {
      width: 0.875rem;
      height: 0.875rem;
      background: var(--ov-on-ink);
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--ov-accent) 45%, transparent);
    }

    .news {
      top: 25rem;
      left: 6rem;
      width: 18.75rem;
      flex-direction: column;
      gap: var(--ov-space-2);
      padding: var(--ov-space-4) var(--ov-space-5);
      font-size: var(--ov-text-sm);
    }

    .who {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      line-height: 1.3;
    }

    .who strong {
      display: block;
    }

    .avatar {
      flex: 0 0 2rem;
      height: 2rem;
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .muted {
      color: var(--ov-ink-muted);
    }

    .change {
      align-self: flex-start;
      margin-top: 0;
      border-radius: var(--ov-radius-sm);
    }

    /* Con poca altura, o con el panel angosto (el título baja a más líneas), la tercera
       tarjeta pisaría "Ver. Seguir. Mostrar.". */
    @media (max-height: 54rem), (max-width: 80rem) {
      .news {
        display: none;
      }
    }

    /* Pantallas bajas: las tarjetas suben y el recorrido se achica, para no tapar el título. */
    @media (min-width: 60rem) and (max-height: 50rem) {
      .progress {
        top: 2.5rem;
      }

      .tour {
        top: 9rem;
        gap: var(--ov-space-2);
      }

      .scene {
        height: 4rem;
      }
    }

    @media (min-width: 60rem) and (max-height: 42rem) {
      .tour {
        display: none;
      }
    }

    /* Teléfono: dos tarjetas en chico dentro de la franja verde. */
    @media (max-width: 59.99rem) {
      .progress {
        top: 3.75rem;
        left: var(--ov-gutter);
        width: 14.5rem;
        gap: var(--ov-space-3);
        padding: var(--ov-space-3) var(--ov-space-4);
      }

      .ring {
        flex-basis: 3rem;
        height: 3rem;
      }

      .ring > div {
        width: 2.25rem;
        height: 2.25rem;
        font-size: var(--ov-text-xs);
      }

      .title {
        font-size: var(--ov-text-md);
      }

      .chip {
        margin-top: var(--ov-space-1);
        padding: 0;
        background: none;
      }

      .tour {
        top: 7.5rem;
        right: -1.25rem;
        width: 12.5rem;
        gap: var(--ov-space-2);
        padding: var(--ov-space-3);
      }

      .tour .faint,
      .months,
      .scene mat-icon,
      .news {
        display: none;
      }

      .scene {
        height: 3.25rem;
      }
    }
  `,
})
export class LoginShowcaseComponent {}
