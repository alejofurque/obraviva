import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivityItem } from '../../core/api/api.models';
import { VariantPipe } from '../../shared/media/variant.pipe';
import { activityLinks, fieldActivityLinks } from './activity-derivations';
import { activityLine, changeChip, initialsOf } from './overview-derivations';

/**
 * Una novedad con el formato de Novedades: quién (con su avatar) y qué, la casa como
 * etiqueta, el punto de sin ver, el texto del avance, el cambio de etapa en un chip, las
 * fotos con su lugar y, al pie, la hora con "Ver en la casa" y "Corregir". El mismo formato
 * en Novedades, en la casa, en "Mis últimas publicaciones" del encargado y en el feed de su
 * casa, donde la casa no se repite.
 */
@Component({
  selector: 'app-activity-item',
  imports: [DatePipe, RouterLink, VariantPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let current = item();
    <article class="item" [class.unseen]="current.unseen">
      <span class="avatar" [class.capture]="current.kind === 'CAPTURE'" [class.supply]="current.kind === 'SUPPLY'" aria-hidden="true">{{ initials() }}</span>
      <div class="body">
        <div class="head">
          <p class="who"><strong>{{ line().who }}</strong>{{ ' ' }}<span class="what">{{ line().what }}</span></p>
          @if (showUnit()) {
            <span class="unit">{{ current.unitCode ?? 'Obras comunes' }}</span>
          }
          @if (current.entry?.source === 'AUDIO') {
            <span class="ov-pill ov-pill--outline tag">Desde un audio</span>
          }
          @if (current.entry?.correctedByName; as by) {
            <span class="ov-pill ov-pill--outline tag">Corregido por {{ by }}</span>
          }
          @if (current.unseen) {
            <span class="dot" aria-label="Sin ver"></span>
          }
        </div>

        @switch (current.kind) {
          @case ('ENTRY') {
            @let entry = current.entry!;
            <p class="text">{{ entry.description }}</p>
            @if (chip(); as change) {
              <p class="change ov-num">{{ change }}</p>
            }
            @if (entry.photos.length > 0) {
              <ul class="thumbs">
                @for (photo of entry.photos.slice(0, 6); track photo.url) {
                  <li>
                    <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" />
                    @if (photo.sector) { <span class="place">{{ photo.sector }}</span> }
                  </li>
                }
              </ul>
            }
          }
          @case ('PHOTOS') {
            <ul class="thumbs">
              @for (photo of current.session!.thumbnails; track photo.url) {
                <li>
                  @if (photo.kind === 'VIDEO') {
                    <video [src]="photo.url" [poster]="photo | variant: 'poster'" preload="metadata" muted playsinline></video>
                  } @else {
                    <img [src]="photo | variant: 'thumb'" alt="" loading="lazy" />
                  }
                  @if (photo.sector) { <span class="place">{{ photo.sector }}</span> }
                </li>
              }
            </ul>
          }
          @case ('SUPPLY') {
            @let alerts = current.supply!.alerts;
            @if (alerts.length > 1) {
              <ul class="alerts">
                @for (alert of alerts; track $index) {
                  <li>{{ alert }}</li>
                }
              </ul>
            }
            <p class="note">Aviso de materiales de un audio: no se publicó.</p>
          }
          @default {
            @let capture = current.capture!;
            @if (capture.state === 'FAILED' && capture.failureReason) {
              <p class="state" data-state="FAILED">{{ capture.failureReason }}</p>
            }
          }
        }

        <p class="actions">
          <span class="when ov-num">{{ current.at | date: 'HH:mm' }}</span>
          <a class="ov-link" [routerLink]="links().view">Ver en la casa</a>
          @if (correctable() && links().correct; as correct) {
            <a class="ov-link" [routerLink]="correct.link" [queryParams]="correct.query">Corregir</a>
          }
        </p>
      </div>
    </article>
  `,
  styles: `
    .item {
      display: flex;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4) var(--ov-space-5);
      border-top: 1px solid var(--ov-line);
    }

    :host(:first-child) .item {
      border-top: 0;
    }

    .item.unseen {
      background: color-mix(in srgb, var(--ov-accent-soft) 35%, var(--ov-surface));
    }

    .avatar {
      display: grid;
      flex: none;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 50%;
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .avatar.capture {
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .avatar.supply {
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
    }

    .alerts {
      margin: var(--ov-space-2) 0 0;
      padding-left: var(--ov-space-5);
    }

    .note {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .body {
      display: grid;
      flex: 1;
      justify-items: start;
      gap: var(--ov-space-2);
      min-width: 0;
    }

    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--ov-space-1) var(--ov-space-3);
    }

    .who {
      font-size: var(--ov-text-sm);
    }

    .what {
      color: var(--ov-ink-muted);
    }

    .unit {
      padding: 0.0625rem var(--ov-space-2);
      border-radius: var(--ov-radius-sm);
      background: var(--ov-canvas);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-xs);
      font-weight: var(--ov-weight-semibold);
    }

    .tag {
      font-size: var(--ov-text-xs);
    }

    .dot {
      align-self: center;
      width: 0.5rem;
      height: 0.5rem;
      border-radius: var(--ov-radius-pill);
      background: var(--ov-accent);
    }

    .text {
      max-width: var(--ov-reading-max);
      font-size: var(--ov-text-sm);
      line-height: 1.5;
    }

    .change {
      padding: 0.1875rem var(--ov-space-2);
      border-radius: var(--ov-radius-sm);
      background: var(--ov-accent-soft);
      color: var(--ov-accent);
      font-size: var(--ov-text-xs);
    }

    .thumbs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ov-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .thumbs li {
      position: relative;
      width: 6rem;
      height: 4.5rem;
      overflow: hidden;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-surface-muted);
    }

    .thumbs img,
    .thumbs video {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .place {
      position: absolute;
      right: 0;
      bottom: 0;
      left: 0;
      overflow: hidden;
      padding: 0 var(--ov-space-1);
      background: var(--ov-glass);
      color: var(--ov-on-media);
      font-size: var(--ov-text-xs);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .state {
      font-size: var(--ov-text-sm);
    }

    .state[data-state='FAILED'] {
      color: var(--ov-danger);
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0 var(--ov-space-4);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .actions .ov-link {
      display: inline-flex;
      align-items: center;
      min-height: 2.75rem;
    }

    @media (max-width: 40rem) {
      .item {
        gap: var(--ov-space-3);
        padding: var(--ov-space-4);
      }
    }
  `,
})
export class ActivityItemComponent {

  readonly item = input.required<ActivityItem>();
  readonly developmentId = input.required<string>();
  /** En la casa la casa es la que se está viendo: no se repite. */
  readonly showUnit = input(true);
  /** En el feed de la casa del encargado, "Corregir" va sólo en lo propio. */
  readonly correctable = input(true);
  /** En el trabajo de campo los enlaces se quedan en Hoy (`fieldActivityLinks`). */
  readonly field = input(false);

  protected readonly links = computed(() => (this.field() ? fieldActivityLinks : activityLinks)(this.item(), this.developmentId()));
  protected readonly line = computed(() => activityLine(this.item()));
  protected readonly chip = computed(() => changeChip(this.item().entry));
  protected readonly initials = computed(() => initialsOf(this.item()));
}
