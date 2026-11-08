import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/session/user-context.service';
import { todayLocal } from '../../shared/time/local-date';
import {
  greetingName, heroEntry, heroTitle, monthStory, schedule, shortDay, sinceLabel, upcoming,
} from './buyer-derivations';
import { FrontCompareComponent } from './front-compare.component';
import { HeroImage, MiCasaHeroComponent } from './mi-casa/mi-casa-hero.component';
import { MiCasaClosingComponent } from './mi-casa/mi-casa-closing.component';
import { MiCasaJourneyComponent } from './mi-casa/mi-casa-journey.component';
import { MiCasaMonthComponent } from './mi-casa/mi-casa-month.component';
import { MiCasaPlacesComponent } from './mi-casa/mi-casa-places.component';
import { MiCasaStatusComponent } from './mi-casa/mi-casa-status.component';
import { MiCasaUpcomingComponent } from './mi-casa/mi-casa-upcoming.component';
import { FrontPiece, coverMissing, frontMissing, liveCover, placesWithMaterial } from './places-derivations';
import { PreviewNoticeComponent } from './preview-notice.component';
import { captureMoments, heroImage, lastUpdate, monthName } from './property-derivations';
import { PropertyViewStore } from './property-view.store';
import { ImagePipe } from '../../shared/media/variant.pipe';

/**
 * "Mi casa", la pantalla de la familia, de arriba abajo: la
 * portada (tu casa hoy, "Tu casa avanza." y la frase del último avance), el avance, la entrega
 * y lo que se hace ahora; lo que pasó este mes; lo que viene, con el render; del proyecto a la
 * realidad; la casa lugar por lugar; el camino de etapas; y el cierre con la casa, los
 * documentos y el enlace para la familia.
 *
 * <p>Tranquiliza sin inventar: cada pieza sale de un dato publicado y, si el dato no está, no
 * aparece. Nunca en rojo: una demora se dice con su motivo. La misma pantalla en Mi propiedad,
 * en "Ver como el comprador" (con los avisos de lo que falta) y en el enlace público (sin
 * saludo, documentos ni compartir).
 */
@Component({
  selector: 'app-property-summary',
  imports: [ImagePipe, 
    DatePipe, MiCasaHeroComponent, MiCasaStatusComponent, MiCasaMonthComponent, MiCasaUpcomingComponent,
    FrontCompareComponent, MiCasaPlacesComponent, MiCasaJourneyComponent, MiCasaClosingComponent, PreviewNoticeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.view(); as view) {
      <div class="ov-page mi-casa">
        @if (notices(); as owner) {
          @if (owner.cover.length > 0) {
            <app-preview-notice title="Portada viva" [pieces]="owner.cover"
                                [developmentId]="owner.developmentId" [unitId]="owner.unitId" />
          }
        }

        <app-mi-casa-hero [heading]="title()" [greeting]="greeting()" [sentence]="sentence()" [context]="context()"
                          [image]="cover()" [tourLink]="hasTour() ? link('recorrido') : null" [hasMonth]="!!story()" [monthLabel]="monthLabel()"
                          (month)="toMonth()" (failed)="photoFailed($event)" />

        <app-mi-casa-status class="status" [view]="view" />

        @if (story(); as month) {
          <app-mi-casa-month [story]="month" [historyLink]="link('historia')" (failed)="photoFailed($event)" />
        }

        @if (next(); as coming) {
          <app-mi-casa-upcoming [upcoming]="coming" [renderUrl]="view.front | image: 'render' : 'screen'" />
        }

        <app-front-compare [front]="view.front" heading="Del proyecto a la realidad"
                           lead="Deslizá para ver cuánto falta para que se vea así." />
        @if (notices(); as owner) {
          @if (owner.front.length > 0) {
            <app-preview-notice title="Del proyecto a la realidad" [pieces]="owner.front"
                                [developmentId]="owner.developmentId" [unitId]="owner.unitId" />
          }
        }

        @if (hasPlaces()) {
          <app-mi-casa-places [places]="view.places" [detailLink]="link('lugares')" (failed)="photoFailed($event)" />
        }

        @if (view.stages.length > 0) {
          <app-mi-casa-journey [stages]="view.stages" [detailLink]="link('etapas')" />
        }

        <app-mi-casa-closing [view]="view" [isPublic]="store.isPublic()" [unitId]="store.unitId()" [basePath]="store.basePath()" />

        @if (updated(); as day) {
          <p class="footer">
            Lo que ves acá lo publica {{ view.development.builder ?? 'la constructora' }} desde la obra.
            Última actualización: {{ day | date: "d 'de' MMMM" }}.
          </p>
        }
      </div>
    }
  `,
  styles: `
    .mi-casa {
      display: grid;
      gap: var(--ov-space-8);
      padding-top: var(--ov-space-5);
    }

    /* Una pieza sin datos no se dibuja, pero su elemento seguiría sumando un hueco a la grilla. */
    .mi-casa > :empty {
      display: none;
    }

    /* Las tarjetas suben sobre el borde de la portada, como en el boceto. */
    .status {
      margin-top: calc(var(--ov-space-8) * -1 + var(--ov-space-4));
    }

    .footer {
      color: var(--ov-ink-muted);
      font-size: var(--ov-text-sm);
      text-align: center;
    }

    @media (max-width: 40rem) {
      .mi-casa {
        gap: var(--ov-space-7);
        padding-top: 0;
      }

      .status {
        margin-top: calc(var(--ov-space-7) * -1 + var(--ov-space-4));
      }
    }
  `,
})
export class PropertySummaryComponent {

  protected readonly store = inject(PropertyViewStore);
  private readonly context$ = inject(UserContextService);
  private readonly auth = inject(AuthService);
  private readonly today = todayLocal();

  /**
   * En "Ver como el comprador", qué falta para la portada viva y para "Del proyecto a la
   * realidad", sólo lo que quien mira puede resolver. Nulo para el comprador y en el enlace.
   */
  protected readonly notices = computed(() => {
    const preview = this.store.preview();
    const unitId = this.store.unitId();
    const front = this.store.view()?.front ?? null;
    if (!preview || !unitId) {
      return null;
    }
    // Sólo lo que quien mira puede resolver: el encargado sube fotos; el render y la marca
    // del frente son del dueño. Sin saber el rol todavía, se muestra lo del encargado.
    const owner = this.context$.developments().find(development => development.id === preview.developmentId)?.role === 'OWNER';
    const solvable = (pieces: FrontPiece[]) => owner ? pieces : pieces.filter(piece => piece === 'photos');
    return {
      developmentId: preview.developmentId, unitId,
      cover: solvable(coverMissing(front)), front: solvable(frontMissing(front)),
    };
  });

  protected readonly title = computed(() => {
    const view = this.store.view();
    return view ? heroTitle(view) : '';
  });

  /** El comprador, por su nombre; en "Ver como el comprador", sin nombre; en el enlace público, nada. */
  protected readonly greeting = computed(() => {
    if (this.store.isPublic()) {
      return null;
    }
    if (this.store.preview()) {
      return 'Hola';
    }
    const name = greetingName(this.auth.currentUser()?.fullName);
    return name ? `Hola, ${name}` : 'Hola';
  });

  private readonly entry = computed(() => {
    const view = this.store.view();
    return view ? heroEntry(view) : null;
  });

  private readonly schedule = computed(() => {
    const view = this.store.view();
    return view ? schedule(view, this.today) : null;
  });

  /** El último avance de la casa, tal cual; y que va en tiempo, sólo si se puede afirmar. */
  protected readonly sentence = computed(() => {
    const entry = this.entry();
    const onTime = this.schedule()?.kind === 'ON_TIME';
    if (!entry) {
      return onTime ? 'La obra va en tiempo.' : null;
    }
    const text = entry.description.trim();
    return onTime ? `${text}${/[.!?]$/.test(text) ? '' : '.'} La obra va en tiempo.` : text;
  });

  protected readonly context = computed(() => {
    const view = this.store.view();
    if (!view) {
      return '';
    }
    const entry = this.entry();
    const parts = [view.unitCode, view.developmentName];
    if (entry) {
      parts.push(`última novedad ${sinceLabel(entry.date, this.today)}`);
    }
    return parts.join(' · ');
  });

  /** La portada viva (la última foto del frente, nunca de la galería); sin ella, la fija. */
  protected readonly cover = computed<HeroImage | null>(() => {
    const view = this.store.view();
    if (!view) {
      return null;
    }
    const live = liveCover(view.front);
    if (live) {
      return { url: live.url, caption: `${live.sector} · ${shortDay(live.date)}` };
    }
    const url = heroImage(view);
    return url ? { url, caption: null } : null;
  });

  protected readonly hasTour = computed(() => captureMoments(this.store.view()?.captures ?? []).length > 0);
  protected readonly story = computed(() => {
    const view = this.store.view();
    return view ? monthStory(view) : null;
  });
  protected readonly monthLabel = computed(() => {
    const month = this.story()?.month;
    return !month || month === this.today.slice(0, 7) ? 'Qué cambió este mes' : `Qué cambió en ${monthName(month).toLowerCase()}`;
  });

  protected readonly next = computed(() => {
    const view = this.store.view();
    return view ? upcoming(view) : null;
  });
  protected readonly hasPlaces = computed(() => placesWithMaterial(this.store.view()?.places ?? []).length > 0);
  protected readonly updated = computed(() => {
    const view = this.store.view();
    return view ? lastUpdate(view) : null;
  });

  protected toMonth(): void {
    document.getElementById('mes')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** Una foto que falla, por ejemplo por URL vencida, renueva la vista una sola vez. */
  protected photoFailed(url: string): void {
    this.store.renewAfterFailure(url);
  }

  protected link(path: string): string[] {
    return [...this.store.basePath(), path];
  }
}
