import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ActivityFilterKind, ActivityItem, PendingAudio, TeamPerson, Unit } from '../../core/api/api.models';
import { ActivityService } from '../../core/api/activity.service';
import { DevelopmentsService } from '../../core/api/developments.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { replaceQuery } from '../../core/routing/query-state';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { MatIconModule } from '@angular/material/icon';
import { ActivityItemComponent } from './activity-item.component';
import { proposalLine, relativeWhen } from './overview-derivations';
import { activityDays } from './activity-derivations';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

const KINDS: { value: ActivityFilterKind | null; label: string }[] = [
  { value: null, label: 'Todo' },
  { value: 'ENTRY', label: 'Avances' },
  { value: 'PHOTOS', label: 'Fotos' },
  { value: 'CAPTURE', label: '3D' },
  { value: 'AUDIO', label: 'Audio' },
  { value: 'SUPPLY', label: 'Materiales' },
];

/**
 * Novedades: lo que publicaron los encargados en todas las casas, del más nuevo al más viejo,
 * agrupado por día. No es aprobación previa: lo publicado ya lo ve el comprador; esto es para
 * que el dueño lo vea todo junto y lo corrija si hace falta, con lo que ya existe.
 *
 * <p>Arriba, los audios que esperan revisión. Lo nuevo desde la última visita lleva un punto;
 * al entrar se marca visto hasta el ítem más nuevo mostrado, no "ahora".
 *
 * <p>Dentro de una casa (con `HouseStore`) es el feed de esa casa: sin el filtro de casa y,
 * como todo feed filtrado, sin marcar visto, porque lo más nuevo de la casa no es lo más
 * nuevo del desarrollo.
 */
@Component({
  selector: 'app-work-activity',
  imports: [RouterLink, MatIconModule, ActivityItemComponent, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ov-page activity">
      <header class="intro">
        <h2>Novedades</h2>
        <p class="lead">
          {{ house ? 'Todo lo que se publicó en esta casa.' : 'Todo lo que se publicó en la obra, de todas las casas.' }}
          @if (!house && unseenCount() > 0) { {{ unseenCount() }} sin ver. }
          El comprador ya lo ve; acá lo revisás y lo corregís si hace falta.
        </p>
      </header>

      @if (audios().length > 0) {
        <section class="ov-card audios" aria-labelledby="audios-title">
          <span class="audio-icon" aria-hidden="true"><mat-icon svgIcon="mic" /></span>
          <div class="audio-text">
            <h3 id="audios-title">{{ audios().length === 1 ? '1 audio para revisar.' : audios().length + ' audios para revisar.' }}</h3>
            <ul class="plain">
              @for (audio of audios(); track audio.extractionId) {
                <li class="audio">
                  <span class="audio-line">
                    {{ audio.authorName ?? 'Alguien' }} · {{ audio.unitCode ?? 'Obras comunes' }} · {{ when(audio.createdAt) }} · {{ proposal(audio) }}
                  </span>
                  <a class="ov-btn ov-btn--primary" [routerLink]="reviewLink(audio)">Revisar</a>
                </li>
              }
            </ul>
          </div>
        </section>
      }

      <div class="filters" role="group" aria-label="Filtros">
        @if (!house) {
          <select class="filter" aria-label="Casa" (change)="setUnit($any($event.target).value)">
            <option value="" [selected]="!unitId()">Todas las casas</option>
            @for (unit of units(); track unit.id) {
              <option [value]="unit.id" [selected]="unit.id === unitId()">{{ unit.code }}</option>
            }
          </select>
        }
        <select class="filter" aria-label="Encargado" (change)="setAuthor($any($event.target).value)">
          <option value="" [selected]="!authorId()">Todos</option>
          @for (person of team(); track person.userId) {
            <option [value]="person.userId" [selected]="person.userId === authorId()">{{ person.fullName ?? person.email }}</option>
          }
        </select>
        @for (option of kinds; track option.label) {
          <button type="button" class="filter chip" [class.active]="option.value === kind()"
                  [attr.aria-pressed]="option.value === kind()" (click)="setKind(option.value)">{{ option.label }}</button>
        }
      </div>

      @if (loading()) {
        <app-loading-state label="Cargando novedades…" />
      } @else if (error(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (items().length === 0) {
        @if (filtered()) {
          <app-empty-state title="No hay novedades con estos filtros" message="Probá con otra casa, otro encargado u otro tipo." />
        } @else {
          <app-empty-state title="Todavía no hay novedades" message="Lo que publiquen los encargados aparece acá." />
        }
      } @else {
        @for (group of days(); track group.day) {
          <section class="day" [attr.aria-label]="group.label">
            <h3 class="day-title">{{ group.label }}</h3>
            <ul class="plain ov-card day-card">
              @for (item of group.items; track item.id) {
                <li><app-activity-item [item]="item" [developmentId]="developmentId" [showUnit]="!house" /></li>
              }
            </ul>
          </section>
        }
        @if (nextCursor()) {
          <button type="button" class="ov-btn ov-btn--secondary more" [disabled]="loadingMore()" (click)="more()">
            {{ loadingMore() ? 'Cargando…' : 'Ver más' }}
          </button>
        }
      }
    </div>
  `,
  styles: `
    .activity {
      display: grid;
      gap: var(--ov-space-5);
    }

    .lead {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    .intro {
      display: grid;
      gap: var(--ov-space-2);
    }

    .plain {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .audios {
      display: flex;
      gap: var(--ov-space-4);
      padding: var(--ov-space-4) var(--ov-space-5);
      border-color: var(--ov-warning, var(--ov-line-strong));
    }

    .audio-icon {
      display: grid;
      flex: none;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: var(--ov-radius-sm);
      background: var(--ov-canvas);
      color: var(--ov-accent);
    }

    .audio-icon mat-icon {
      width: 1.25rem;
      height: 1.25rem;
    }

    .audio-text {
      display: grid;
      flex: 1;
      gap: var(--ov-space-1);
      min-width: 0;
    }

    .audio-line {
      color: var(--ov-ink-muted);
    }

    h3 {
      font-size: var(--ov-text-md);
    }

    .audio {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--ov-space-2) var(--ov-space-4);
      padding: var(--ov-space-1) 0;
      font-size: var(--ov-text-sm);
    }

    .audio + .audio {
      border-top: 1px solid var(--ov-line);
    }

    /* Una fila; en el teléfono se desplaza de costado. */
    .filters {
      display: flex;
      gap: var(--ov-space-2);
      padding-bottom: var(--ov-space-1);
      overflow-x: auto;
      scrollbar-width: none;
    }

    .filter {
      flex: none;
      min-height: 2.75rem;
      padding: 0 var(--ov-space-4);
      border: 1px solid var(--ov-line-strong);
      border-radius: var(--ov-radius-pill);
      background: var(--ov-surface);
      color: var(--ov-ink);
      font: inherit;
      font-size: var(--ov-text-sm);
      cursor: pointer;
    }

    .chip.active {
      border-color: var(--ov-ink);
      background: var(--ov-ink);
      color: var(--ov-on-ink);
    }

    .day {
      display: grid;
      gap: var(--ov-space-2);
    }

    .day-title {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      font-weight: var(--ov-weight-semibold);
    }

    .day-card {
      overflow: hidden;
      padding: 0;
    }

    .more {
      justify-self: center;
    }
  `,
})
export class WorkActivityComponent implements OnInit {

  private readonly store = inject(WorkStore);
  protected readonly house = inject(HouseStore, { optional: true });
  private readonly activity = inject(ActivityService);
  private readonly developments = inject(DevelopmentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly kinds = KINDS;
  /**
   * Lo sin ver de esta visita: los ítems con punto. Al entrar se marca visto, así que el
   * contador del desarrollo ya vuelve a cero; los puntos se quedan hasta la próxima.
   */
  protected readonly unseenCount = computed(() => this.items().filter(item => item.unseen).length);
  protected readonly items = signal<ActivityItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly audios = signal<PendingAudio[]>([]);
  protected readonly units = signal<Unit[]>([]);
  protected readonly team = signal<TeamPerson[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  /** Los filtros viven en la dirección (`?casa=&persona=&tipo=`): volver con el atrás los conserva. */
  private readonly initialQuery = this.route.snapshot.queryParamMap;

  protected readonly unitId = signal<string | null>(this.house?.unitId() ?? this.initialQuery.get('casa'));
  protected readonly authorId = signal<string | null>(this.initialQuery.get('persona'));
  protected readonly kind = signal<ActivityFilterKind | null>(
    KINDS.find(option => option.value !== null && option.value === this.initialQuery.get('tipo'))?.value ?? null);
  protected readonly filtered = computed(() => !!this.unitId() || !!this.authorId() || !!this.kind());

  private readonly today = todayLocal();
  protected readonly days = computed(() => activityDays(this.items(), this.today));

  protected get developmentId(): string {
    return this.store.development()?.id ?? '';
  }

  ngOnInit(): void {
    // Lo de arriba y los filtros son apoyo: si fallan, el feed se muestra igual.
    forkJoin({
      audios: this.activity.pendingAudios(this.developmentId),
      units: this.developments.units(this.developmentId),
      team: this.developments.team(this.developmentId),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ audios, units, team }) => {
        this.audios.set(this.house ? audios.filter(audio => audio.unitId === this.house!.unitId()) : audios);
        this.units.set(units);
        this.team.set(team.assigned);
      },
      error: () => {},
    });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.activity.feed(this.developmentId, this.query(null)).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: page => {
        this.items.set(page.items);
        this.nextCursor.set(page.nextCursor);
        this.loading.set(false);
        if (!this.filtered()) {
          this.markSeen(page.items);
        }
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.error.set(errorMessageOf(error, 'No se pudieron cargar las novedades.'));
      },
    });
  }

  protected more(): void {
    const cursor = this.nextCursor();
    if (!cursor) return;
    this.loadingMore.set(true);
    this.activity.feed(this.developmentId, this.query(cursor)).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: page => {
        this.items.update(items => [...items, ...page.items]);
        this.nextCursor.set(page.nextCursor);
        this.loadingMore.set(false);
      },
      error: (error: unknown) => {
        this.loadingMore.set(false);
        this.error.set(errorMessageOf(error, 'No se pudieron cargar más novedades.'));
      },
    });
  }

  protected setUnit(value: string): void {
    this.unitId.set(value || null);
    replaceQuery(this.router, this.route, { casa: value || null });
    this.load();
  }

  protected setAuthor(value: string): void {
    this.authorId.set(value || null);
    replaceQuery(this.router, this.route, { persona: value || null });
    this.load();
  }

  protected setKind(value: ActivityFilterKind | null): void {
    this.kind.set(value);
    replaceQuery(this.router, this.route, { tipo: value });
    this.load();
  }

  protected when(at: string): string {
    return relativeWhen(at);
  }

  protected proposal(audio: PendingAudio): string {
    return proposalLine(audio.progressNotes ?? 0, audio.stageChanges ?? 0);
  }

  protected reviewLink(audio: PendingAudio): string[] {
    const place = audio.unitId ? ['casas', audio.unitId] : ['comunes'];
    return ['/obras', this.developmentId, ...place, 'historia', 'audios', audio.extractionId];
  }

  private query(before: string | null) {
    return { unitId: this.unitId(), authorId: this.authorId(), kind: this.kind(), before };
  }

  /**
   * Visto hasta el ítem más nuevo mostrado. Los puntos de esta visita se quedan: se
   * apagan la próxima. Sólo sin filtros: filtrado, lo más nuevo mostrado no es lo más nuevo.
   */
  private markSeen(items: ActivityItem[]): void {
    const newest = items[0]?.at;
    if (!newest) {
      this.store.clearUnseen();
      return;
    }
    this.activity.markSeen(this.developmentId, newest).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.store.clearUnseen(),
      error: () => {},
    });
  }
}
