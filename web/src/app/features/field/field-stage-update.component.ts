import { ChangeDetectionStrategy, Component } from '@angular/core';
import { StageUpdateBase } from '../../shared/ui/stage-update.component';

/**
 * "Actualizar la etapa" en "Publicar avance" del encargado: las mismas reglas que
 * `StageUpdateComponent`, en grande para usarlo en la obra con una mano. El interruptor, −5 y
 * +5 de 56 px alrededor del número, la barra y los tres estados como botones; Demorado en
 * ámbar. Sin campo para escribir el número: el porcentaje se pone con ±5.
 */
@Component({
  selector: 'app-field-stage-update',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let chosen = stage();
    <section class="update" aria-label="Avance de la etapa">
      <label class="switch">
        <span class="what">
          <span class="title">Actualizar la etapa</span>
          <span class="sub">El comprador ve el nuevo porcentaje</span>
        </span>
        <input type="checkbox" role="switch" [checked]="on()" [disabled]="disabled()"
               (change)="toggle($any($event.target).checked)" />
      </label>

      @if (on()) {
        <div class="stepper">
          <button type="button" class="step" aria-label="Restar 5%"
                  [disabled]="locked() || disabled() || percent() <= 0" (click)="step(-5)">−5</button>
          <div class="value">
            <output class="ov-num" [id]="idPrefix() + '-percent'" aria-live="polite"
                    [attr.aria-label]="'Avance de ' + chosen.name">{{ percent() }}%</output>
            <span class="before">antes {{ chosen.progressPercent }}%</span>
          </div>
          <button type="button" class="step" aria-label="Sumar 5%"
                  [disabled]="locked() || disabled() || percent() >= 100" (click)="step(5)">+5</button>
        </div>
        <span class="ov-progress bar" aria-hidden="true"><span [style.width.%]="percent()"></span></span>
        @if (locked()) {
          <p class="hint">Completada, la etapa queda en 100%.</p>
        } @else if (percent() < chosen.progressPercent) {
          <p class="warning">Baja el avance que ve el comprador: estaba en {{ chosen.progressPercent }}%.</p>
        }

        <fieldset class="statuses" [disabled]="disabled()">
          <legend class="hint">Estado de la etapa</legend>
          <div class="options">
            @for (option of statuses; track option.value) {
              <label [class.active]="status() === option.value" [attr.data-status]="option.value">
                <input type="radio" [name]="idPrefix() + '-status'" [value]="option.value" [checked]="status() === option.value"
                       (change)="chooseStatus(option.value)" />
                {{ option.label }}
              </label>
            }
          </div>
          @if (status() === 'DELAYED') {
            <p class="hint">El texto del avance es la explicación que ve el comprador: contá por qué se demora.</p>
          }
        </fieldset>
      }
    </section>
  `,
  styles: `
    .update {
      display: grid;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    .switch {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-3);
      min-height: 3rem;
      cursor: pointer;
    }

    .what {
      display: grid;
    }

    .title {
      font-weight: var(--ov-weight-semibold);
    }

    .sub,
    .hint,
    .before {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .switch input {
      flex: 0 0 3.25rem;
      position: relative;
      width: 3.25rem;
      height: 2rem;
      margin: 0;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-line-strong);
      appearance: none;
      cursor: pointer;
      transition: background 120ms;
    }

    .switch input::before {
      content: '';
      position: absolute;
      top: 3px;
      left: 3px;
      width: 1.625rem;
      height: 1.625rem;
      border-radius: 50%;
      background: var(--ov-surface);
      transition: transform 120ms;
    }

    .switch input:checked {
      background: var(--ov-accent);
    }

    .switch input:checked::before {
      transform: translateX(1.25rem);
    }

    .switch input:focus-visible {
      outline: 2px solid var(--ov-accent);
      outline-offset: 2px;
    }

    .stepper {
      display: grid;
      grid-template-columns: 3.5rem minmax(0, 1fr) 3.5rem;
      align-items: center;
      gap: var(--ov-space-3);
    }

    .step {
      height: 3.5rem;
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      background: var(--ov-canvas);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-lg);
      font-weight: var(--ov-weight-semibold);
      cursor: pointer;
    }

    .step:disabled {
      color: var(--ov-ink-muted);
      cursor: default;
    }

    .value {
      display: grid;
      justify-items: center;
    }

    output {
      font-size: 2.5rem;
      font-weight: var(--ov-weight-semibold);
      letter-spacing: -0.02em;
      line-height: 1.1;
    }

    .warning {
      color: var(--ov-status-delayed);
      font-size: var(--ov-text-sm);
    }

    .statuses {
      display: grid;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      border: 0;
    }

    .options {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--ov-space-2);
    }

    .options label {
      display: grid;
      place-items: center;
      min-height: 3rem;
      padding: 0 var(--ov-space-1);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-md);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
      text-align: center;
      cursor: pointer;
    }

    .options label.active {
      border-color: var(--ov-accent);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .options label.active[data-status='DELAYED'] {
      border-color: var(--ov-status-delayed);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .options label:focus-within {
      outline: 2px solid var(--ov-accent);
      outline-offset: 1px;
    }

    .options input {
      position: absolute;
      opacity: 0;
      pointer-events: none;
    }
  `,
})
export class FieldStageUpdateComponent extends StageUpdateBase {}
