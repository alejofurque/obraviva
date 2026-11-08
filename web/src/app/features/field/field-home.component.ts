import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ActivityItem, AssignedHouse, ExtractionSummary, Stage } from '../../core/api/api.models';
import { ActivityService } from '../../core/api/activity.service';
import { AudioExtractionsService } from '../../core/api/audio-extractions.service';
import { CapturesService } from '../../core/api/captures.service';
import { ProgressService } from '../../core/api/progress.service';
import { StagesService } from '../../core/api/stages.service';
import { AuthService } from '../../core/auth/auth.service';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { AccountMenuComponent } from '../../shell/account-menu.component';
import {
  audioNotice,
  audioNoticeLine,
  firstName,
  lastPublicationLine,
  openStages,
  sessionPlaceProgress,
  sessionPlaces,
  sessionStatus,
  todayHeading,
} from './field-derivations';
import { FieldHouseAsideComponent } from './field-house-aside.component';
import { FieldHousePickerComponent } from './field-house-picker.component';
import { FieldRecentComponent } from './field-recent.component';
import { FieldHouseStore } from './field-house.store';
import { FieldStagesComponent } from './field-stages.component';

/**
 * "Hoy", el inicio del encargado, pensado para el teléfono y para usarse al sol.
 * De arriba abajo: la fecha y el saludo, con la cuenta en
 * el avatar; la casa como tarjeta que se toca para cambiarla; si tiene audios listos, el aviso
 * en ámbar; "Publicar avance" como acción principal; "Recorrido de hoy" (con cuántos lugares ya
 * tienen material) y "Contarlo con audio" lado a lado; las etapas en curso; y su última
 * publicación en una línea, que lleva a "Mis publicaciones".
 *
 * <p>Las casas y la elegida son de `FieldHouseStore`. Lo demás (audios, la sesión del día, las
 * etapas y lo último publicado) es de apoyo: si algo falla, esa parte no se muestra y el resto
 * sigue.
 */
@Component({
  selector: 'app-field-home',
  imports: [RouterLink, MatIconModule, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent,
    AccountMenuComponent, FieldHouseAsideComponent, FieldHousePickerComponent, FieldRecentComponent, FieldStagesComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <header class="greeting">
        <div>
          <p class="date">{{ heading }}</p>
          <h1>Hola{{ name() ? ', ' + name() : '' }}</h1>
        </div>
        <app-account-menu class="account" size="large" />
      </header>

      @if (store.loading() && store.houses().length === 0) {
        <app-loading-state label="Cargando tus casas…" />
      } @else if (store.error(); as message) {
        <app-error-state [message]="message" (retry)="reload()" />
      } @else if (store.houses().length === 0) {
        <app-empty-state title="Todavía no tenés casas asignadas"
          message="Cuando el responsable del desarrollo te asigne una casa, la vas a ver acá para cargar sus avances." />
      } @else if (store.selected(); as house) {
        <!-- En el teléfono la casa se elige acá; en escritorio, en la barra de arriba. -->
        <app-field-house-picker class="phone-only" [house]="house" [houses]="store.houses()" (picked)="store.select($event.unitId)" />

        @if (audios(); as notice) {
          @if (notice.ready.length + notice.processing.length > 0) {
          <div class="alerts">
          @if (notice.ready.length > 0) {
            <a class="notice" role="status" [routerLink]="['/hoy', house.unitId, 'audio', notice.ready[0].id]">
              <mat-icon svgIcon="mic" aria-hidden="true" />
              <span class="notice-text">{{ noticeLine(notice.ready.length) }}</span>
              <span class="notice-go">Revisar</span>
            </a>
          }
          @if (notice.processing.length > 0) {
            <a class="processing" [routerLink]="['/hoy', house.unitId, 'audio', notice.processing[0].id]">
              {{ notice.processing.length === 1 ? 'Un audio tuyo se está procesando' : notice.processing.length + ' audios tuyos se están procesando' }}
            </a>
          }
          </div>
          }
        }

        <div class="main">
        <nav class="actions" aria-label="Qué actualizar">
          <a class="primary" [routerLink]="['/hoy', house.unitId, 'avance']">
            <span class="plus"><mat-icon svgIcon="add" aria-hidden="true" /></span>
            <span class="action-text">
              <span class="action-name">Publicar avance</span>
              <span class="action-hint">Qué se hizo, fotos y cuánto avanzó</span>
            </span>
          </a>
          <a class="card" [routerLink]="['/hoy', house.unitId, 'recorrido']">
            <mat-icon svgIcon="view_in_ar" aria-hidden="true" />
            <span class="action-name">Recorrido de hoy</span>
            <span class="action-hint">{{ sessionLine(house) }}</span>
          </a>
          <a class="card" [routerLink]="['/hoy', house.unitId, 'audio']">
            <mat-icon svgIcon="mic" aria-hidden="true" />
            <span class="action-name">Contarlo con audio</span>
            <span class="action-hint phone-only">La IA lo ordena, vos revisás</span>
            <span class="action-hint desktop-only">O subí un audio de WhatsApp</span>
          </a>
        </nav>

        @if (stages(); as list) {
          <section class="stages phone-only" aria-labelledby="stages-title">
            <h2 id="stages-title">Etapas en curso</h2>
            <app-field-stages [stages]="list" [unitId]="house.unitId" />
          </section>
        }

        <!-- La línea y el enlace se separan con espacio, no con "·": al cortarse no queda colgando. -->
        <p class="last phone-only">
          @if (latest(); as item) {
            <span>Tu última publicación: {{ lastLine(item) }}</span>
          } @else if (latestLoaded()) {
            <span>Todavía no publicaste nada en esta casa</span>
          }
          <a [routerLink]="['/hoy', house.unitId, 'publicaciones']">Mis publicaciones</a>
        </p>

        <app-field-recent class="desktop-only" [items]="recent()" [loaded]="latestLoaded()"
                          [unitId]="house.unitId" [developmentId]="house.developmentId" />
        </div>

        <app-field-house-aside class="aside desktop-only" [house]="house" [stages]="stages()" />
      }
    </div>
  `,
  styles: `
    .field {
      display: grid;
      gap: var(--ov-space-4);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-5) var(--ov-gutter) var(--ov-space-6);
    }

    .greeting {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--ov-space-4);
    }

    .date {
      margin: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-medium);
    }

    h1 {
      margin: var(--ov-space-1) 0 0;
      font-size: var(--ov-text-xl);
    }

    .notice {
      display: flex;
      align-items: center;
      gap: var(--ov-space-3);
      min-height: 3.5rem;
      padding: var(--ov-space-3) var(--ov-space-4);
      border: 1px solid color-mix(in srgb, var(--ov-status-delayed) 45%, transparent);
      border-radius: var(--ov-radius-md);
      background: var(--ov-status-delayed-soft);
      color: var(--ov-status-delayed);
      font-weight: var(--ov-weight-semibold);
      text-decoration: none;
    }

    .notice-text {
      flex: 1;
    }

    .notice-go {
      text-decoration: underline;
    }

    .processing {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--ov-space-3);
    }

    .primary {
      display: flex;
      grid-column: 1 / -1;
      align-items: center;
      gap: var(--ov-space-4);
      min-height: 5.5rem;
      padding: var(--ov-space-4) var(--ov-space-5);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
      text-decoration: none;
    }

    .plus {
      display: grid;
      flex: 0 0 3rem;
      height: 3rem;
      place-items: center;
      border-radius: 50%;
      background: color-mix(in srgb, var(--ov-on-ink) 16%, transparent);
    }

    .action-text {
      display: grid;
      gap: 2px;
    }

    .primary .action-name {
      font-size: var(--ov-text-lg);
    }

    .primary .action-hint {
      color: color-mix(in srgb, var(--ov-on-ink) 75%, transparent);
    }

    .card {
      display: grid;
      align-content: start;
      gap: var(--ov-space-1);
      min-height: 7.5rem;
      padding: var(--ov-space-4);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
      color: var(--ov-ink);
      text-decoration: none;
    }

    .card mat-icon {
      width: 1.75rem;
      height: 1.75rem;
      margin-bottom: var(--ov-space-2);
      color: var(--ov-accent);
    }

    .action-name {
      font-weight: var(--ov-weight-semibold);
    }

    .action-hint {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .stages {
      padding: var(--ov-space-4) var(--ov-space-5);
      border: 1px solid var(--ov-line);
      border-radius: var(--ov-radius-lg);
      background: var(--ov-surface);
    }

    h2 {
      margin: 0 0 var(--ov-space-2);
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .main,
    .alerts {
      display: grid;
      gap: var(--ov-space-4);
    }

    .last {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      column-gap: var(--ov-space-3);
      margin: 0;
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .last a {
      display: inline-flex;
      align-items: center;
      min-height: 3rem;
      color: var(--ov-accent);
      font-weight: var(--ov-weight-semibold);
    }

    .desktop-only {
      display: none;
    }

    /*
     * Escritorio: el saludo con el aviso al lado; debajo, las acciones
     * en fila y "Mis últimas publicaciones", y al costado la casa. La cuenta y el selector de casa
     * van en la barra de arriba.
     */
    @media (min-width: 60rem) {
      .field {
        grid-template-columns: minmax(0, 2fr) minmax(20rem, 1fr);
        grid-template-areas: 'greeting alerts' 'main aside';
        align-items: start;
        column-gap: var(--ov-space-6);
        max-width: 75rem;
        padding-top: var(--ov-space-6);
      }

      /* Los estados (cargando, error, sin casas) ocupan todo el ancho; el resto va por su área. */
      app-loading-state,
      app-error-state,
      app-empty-state {
        grid-column: 1 / -1;
      }

      .greeting { grid-area: greeting; }
      .alerts { grid-area: alerts; align-self: center; }
      .main { grid-area: main; gap: var(--ov-space-6); }
      .aside { grid-area: aside; }

      .account,
      .phone-only {
        display: none;
      }

      .desktop-only {
        display: block;
      }

      .actions {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }

      .primary {
        grid-column: auto;
        flex-direction: column;
        align-items: flex-start;
        justify-content: space-between;
      }

      .primary .plus {
        flex: none;
        width: 3rem;
      }

      .primary,
      .card {
        min-height: 9.5rem;
        padding: var(--ov-space-5);
      }
    }
  `,
})
export class FieldHomeComponent implements OnInit {
  protected readonly store = inject(FieldHouseStore);
  private readonly audioService = inject(AudioExtractionsService);
  private readonly activity = inject(ActivityService);
  private readonly capturesService = inject(CapturesService);
  private readonly progressService = inject(ProgressService);
  private readonly stagesService = inject(StagesService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly heading = todayHeading();
  private readonly today = todayLocal();
  protected readonly name = computed(() => firstName(this.auth.currentUser()?.fullName));
  protected readonly noticeLine = audioNoticeLine;

  private readonly audioList = signal<ExtractionSummary[]>([]);
  /** Cuántos lugares de la casa elegida ya tienen material hoy, como en la sesión de recorrido. */
  private readonly session = signal<{ unitId: string; done: number; total: number } | null>(null);
  /** Las etapas abiertas de la casa elegida; nulo mientras no cargan o si fallaron. */
  protected readonly stages = signal<Stage[] | null>(null);
  /** Lo último que publicó la persona en la casa elegida. */
  protected readonly latest = signal<ActivityItem | null>(null);
  /** Las últimas cinco, para "Mis últimas publicaciones" en escritorio; la primera es `latest`. */
  protected readonly recent = signal<ActivityItem[]>([]);
  protected readonly latestLoaded = signal(false);

  /** Los audios propios de la casa elegida: los listos para revisar van como aviso arriba. */
  protected readonly audios = computed(() => {
    const house = this.store.selected();
    return house ? audioNotice(this.audioList(), house.unitId) : null;
  });

  /** La casa elegida por su id: si las casas se vuelven a pedir, lo de la casa no se pide dos veces. */
  private readonly selectedUnitId = computed(() => this.store.selected()?.unitId ?? null);

  constructor() {
    // Lo de la casa se vuelve a pedir cuando cambia la elegida, desde acá o desde la barra.
    effect(() => {
      if (this.selectedUnitId()) {
        untracked(() => this.loadSupport(this.store.selected()!));
      }
    });
  }

  ngOnInit(): void {
    this.store.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => {} });
  }

  protected reload(): void {
    this.store.load(true).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ error: () => {} });
  }

  protected lastLine(item: ActivityItem): string {
    return lastPublicationLine(item, this.today);
  }

  /** "3D y fotos · 2 de 5 lugares": el conteo es el mismo que muestra la sesión; si no se pudo calcular, va sin él. */
  protected sessionLine(house: AssignedHouse): string {
    const base = '3D y fotos';
    if (house.sectors.length === 0) {
      return base + ' · sin lugares definidos';
    }
    const session = this.session();
    return session?.unitId === house.unitId ? `${base} · ${session.done} de ${session.total} lugares` : base;
  }

  private loadSupport(house: AssignedHouse): void {
    this.stages.set(null);
    this.latest.set(null);
    this.recent.set([]);
    this.latestLoaded.set(false);
    this.session.set(null);

    this.audioService.listByDevelopment(house.developmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: audios => this.audioList.set(audios),
      error: () => this.audioList.set([]),
    });

    this.stagesService.listByUnit(house.unitId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: stages => this.stages.set(openStages(stages)),
      error: () => this.stages.set(null),
    });

    const authorId = this.auth.currentUser()?.id;
    if (authorId) {
      this.activity.feed(house.developmentId, { unitId: house.unitId, authorId, limit: 5 })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: page => {
            this.latest.set(page.items[0] ?? null);
            this.recent.set(page.items);
            this.latestLoaded.set(true);
          },
          error: () => this.latestLoaded.set(false),
        });
    }

    if (house.sectors.length > 0) {
      forkJoin({
        captures: this.capturesService.listByDevelopment(house.developmentId),
        processing: this.capturesService.inProcess(house.developmentId),
        media: this.progressService.media(house.developmentId),
      }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: ({ captures, processing, media }) => {
          const statuses = sessionStatus(house.sectors, captures, processing, house.unitId, this.today);
          const progress = sessionPlaceProgress(sessionPlaces(statuses, media, captures, processing, house.unitId, this.today));
          this.session.set({ unitId: house.unitId, ...progress });
        },
        error: () => this.session.set(null),
      });
    }
  }
}
