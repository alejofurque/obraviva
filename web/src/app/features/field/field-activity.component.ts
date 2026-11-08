import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { ActivityItem, AssignedHouse } from '../../core/api/api.models';
import { ActivityService } from '../../core/api/activity.service';
import { DevelopmentsService } from '../../core/api/developments.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessageOf } from '../../shared/http/error-message';
import { EmptyStateComponent, ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/page-states.component';
import { todayLocal } from '../../shared/time/local-date';
import { ActivityItemComponent } from '../works/activity-item.component';
import { activityDays } from '../works/activity-derivations';

const PAGE = 20;

/**
 * "Mis publicaciones", la pestaña del trabajo de campo: lo que publicó la persona en la casa
 * (el feed acepta `authorId`), del más nuevo al más viejo y por día, todo corregible. El
 * mismo formato que Novedades, sin la marca de visto (es del dueño).
 */
@Component({
  selector: 'app-field-activity',
  imports: [ActivityItemComponent, LoadingStateComponent, ErrorStateComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      @if (loading()) {
        <app-loading-state label="Cargando lo de la casa…" />
      } @else if (error(); as message) {
        <app-error-state [message]="message" (retry)="load()" />
      } @else if (house(); as current) {
        <header>
          <p class="place">{{ current.developmentName }}</p>
          <h1>Mis publicaciones</h1>
          <p class="place">{{ current.code }}</p>
        </header>
        @if (items().length === 0) {
          <app-empty-state title="Todavía no publicaste nada en esta casa"
            message="Lo que publiques, las fotos que subas y los recorridos que grabes aparecen acá." />
        } @else {
          @for (group of days(); track group.day) {
            <section class="day" [attr.aria-label]="group.label">
              <h2 class="day-title">{{ group.label }}</h2>
              <ul class="plain ov-card feed">
                @for (item of group.items; track item.id) {
                  <li>
                    <app-activity-item [item]="item" [developmentId]="current.developmentId" [showUnit]="false" [field]="true"
                                       [correctable]="item.authorId === userId()" />
                  </li>
                }
              </ul>
            </section>
          }
          @if (nextCursor()) {
            <button type="button" class="ov-btn ov-btn--secondary more" [disabled]="loadingMore()" (click)="more()">
              {{ loadingMore() ? 'Cargando…' : 'Ver más' }}
            </button>
          }
          @if (moreError(); as message) {
            <p class="more-error" role="alert">{{ message }}</p>
          }
        }
      }
    </div>
  `,
  styles: `
    .field {
      display: grid;
      gap: var(--ov-space-5);
      width: 100%;
      max-width: 30rem;
      margin: 0 auto;
      padding: var(--ov-space-5) var(--ov-gutter) var(--ov-space-8);
    }


    .back::before {
      content: '‹ ';
    }

    .place {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
    }

    h1 {
      font-size: var(--ov-text-xl);
      letter-spacing: -0.02em;
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

    .plain {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .feed {
      overflow: hidden;
      padding: 0;
    }

    .more {
      justify-self: center;
    }

    .more-error {
      color: var(--ov-danger);
      font-size: var(--ov-text-sm);
      text-align: center;
    }
  `,
})
export class FieldActivityComponent implements OnInit {

  private readonly route = inject(ActivatedRoute);
  private readonly developments = inject(DevelopmentsService);
  private readonly activity = inject(ActivityService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly unitId = this.route.snapshot.paramMap.get('unitId') ?? '';
  private readonly today = todayLocal();
  protected readonly userId = computed(() => this.auth.currentUser()?.id ?? null);
  protected readonly house = signal<AssignedHouse | null>(null);
  protected readonly items = signal<ActivityItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly moreError = signal<string | null>(null);
  protected readonly days = computed(() => activityDays(this.items(), this.today));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.developments.myWork().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: houses => {
        const house = houses.find(candidate => candidate.unitId === this.unitId) ?? null;
        if (!house) {
          this.loading.set(false);
          this.error.set('No estás asignado a esta casa.');
          return;
        }
        this.house.set(house);
        this.page(null);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.error.set(errorMessageOf(error, 'No se pudo cargar la casa.'));
      },
    });
  }

  protected more(): void {
    const cursor = this.nextCursor();
    if (cursor) {
      this.loadingMore.set(true);
      this.moreError.set(null);
      this.page(cursor);
    }
  }

  private page(before: string | null): void {
    const house = this.house()!;
    const authorId = this.userId() ?? undefined;
    this.activity.feed(house.developmentId, { unitId: house.unitId, authorId, before, limit: PAGE })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: page => {
          this.items.update(items => before ? [...items, ...page.items] : page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadingMore.set(false);
          const message = errorMessageOf(error, 'No se pudo cargar lo de la casa.');
          if (before) {
            this.moreError.set(message);
          } else {
            this.error.set(message);
          }
        },
      });
  }
}
