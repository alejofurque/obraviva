import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';
import { homeRedirectGuard } from './core/session/home.guard';
import { ownerGuard } from './core/session/owner.guard';
import { supervisorGuard } from './core/session/supervisor.guard';
import { commonWorksGuard } from './core/session/common-works.guard';
import { leaveGuard } from './shared/ui/leave-guard';

/**
 * Pestañas de una propiedad. Las comparten "Mi propiedad" y el enlace público: el
 * backend les devuelve la misma proyección y las pantallas no se duplican.
 */
const PROPERTY_TABS: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./features/property/property-summary.component').then(m => m.PropertySummaryComponent),
  },
  {
    path: 'recorrido',
    loadComponent: () =>
      import('./features/property/property-tour.component').then(m => m.PropertyTourComponent),
  },
  {
    path: 'casa',
    loadComponent: () =>
      import('./features/property/property-home.component').then(m => m.PropertyHomeComponent),
  },
  {
    path: 'historia',
    loadComponent: () =>
      import('./features/property/property-history.component').then(m => m.PropertyHistoryComponent),
  },
  {
    path: 'planos',
    loadComponent: () =>
      import('./features/property/property-plans.component').then(m => m.PropertyPlansComponent),
  },
  // Desde "Mi casa": el detalle de un lugar (?lugar=) y el camino completo de etapas. Sin pestaña.
  {
    path: 'lugares',
    loadComponent: () =>
      import('./features/property/property-place-detail.component').then(m => m.PropertyPlaceDetailComponent),
  },
  {
    path: 'etapas',
    loadComponent: () =>
      import('./features/property/property-place-detail.component').then(m => m.PropertyStagesComponent),
  },
];

/**
 * Historia, audios, proyecto y realidad y recorridos: los mismos dentro de una casa y en
 * las obras comunes. La pantalla sabe dónde está por el HouseStore que provee la casa.
 */
function scopedWorkRoutes(): Routes {
  return [
    {
      path: 'historia',
      canDeactivate: [leaveGuard],
      loadComponent: () => import('./features/works/work-progress.component').then(m => m.WorkProgressComponent),
    },
    {
      path: 'historia/comparaciones',
      loadComponent: () => import('./features/works/work-comparisons.component').then(m => m.WorkComparisonsComponent),
    },
    {
      path: 'historia/audios',
      loadComponent: () => import('./features/works/work-audios.component').then(m => m.WorkAudiosComponent),
    },
    {
      path: 'historia/audios/:extractionId',
      canDeactivate: [leaveGuard],
      loadComponent: () => import('./features/works/work-audio-review.component').then(m => m.WorkAudioReviewComponent),
    },
    {
      path: 'recorridos',
      loadComponent: () => import('./features/works/work-captures.component').then(m => m.WorkCapturesComponent),
    },
    {
      path: 'recorridos/visor',
      loadComponent: () => import('./features/works/work-viewer.component').then(m => m.WorkViewerComponent),
    },
  ];
}

/**
 * Todas las pantallas se cargan bajo demanda: el paquete inicial queda con lo mínimo
 * para arrancar, y cada sección baja cuando se abre.
 *
 * <p>Las pantallas rediseñadas viven dentro del marco (ShellComponent). Las que todavía
 * no pasaron por su rediseño traen su propia barra y quedan afuera hasta entonces, para
 * no mostrar dos encabezados.
 */
export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent),
  },

  // Vista pública: sin authGuard, es el punto del enlace. La ruta es corta porque
  // la gente copia y pega esta dirección en mensajes, y el token ya son 43 caracteres.
  {
    path: 'p/:token',
    loadComponent: () =>
      import('./features/property/property-layout.component').then(m => m.PropertyLayoutComponent),
    children: PROPERTY_TABS,
  },

  // Entrada: decide adónde va cada persona según lo que tiene.
  { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },

  {
    path: '',
    canActivate: [authGuard],
    // En cada navegación, no sólo al entrar: si la sesión se borró (se venció o se cerró en otra
    // pestaña), la próxima pantalla lleva al ingreso en vez de quedarse sin datos.
    runGuardsAndResolvers: 'always',
    loadComponent: () => import('./shell/shell.component').then(m => m.ShellComponent),
    children: [
      {
        path: 'inicio',
        loadComponent: () => import('./features/home/home.component').then(m => m.HomeComponent),
      },
      {
        path: 'cuenta',
        loadComponent: () => import('./features/account/account.component').then(m => m.AccountComponent),
      },
      // Trabajo de campo del encargado, pensado para el celular. Las tres secciones (data.tabs)
      // llevan la barra de abajo en el teléfono; publicar, el recorrido y el audio son subpantallas.
      {
        path: 'hoy',
        canActivate: [supervisorGuard],
        loadComponent: () => import('./features/field/field-layout.component').then(m => m.FieldLayoutComponent),
        children: [
          {
            path: '',
            data: { tabs: true },
            loadComponent: () => import('./features/field/field-home.component').then(m => m.FieldHomeComponent),
          },
          {
            path: ':unitId/casa',
            data: { reloadOnParams: true, tabs: true },
            loadComponent: () => import('./features/field/field-house.component').then(m => m.FieldHouseComponent),
          },
          {
            path: ':unitId/publicaciones',
            data: { reloadOnParams: true, tabs: true },
            loadComponent: () => import('./features/field/field-activity.component').then(m => m.FieldActivityComponent),
          },
          {
            path: ':unitId/avance',
            data: { reloadOnParams: true },
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/field/field-progress.component').then(m => m.FieldProgressComponent),
          },
          // "Corregir" desde Mis publicaciones: la misma pantalla, con el avance cargado.
          {
            path: ':unitId/avance/:entryId',
            data: { reloadOnParams: true },
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/field/field-progress.component').then(m => m.FieldProgressComponent),
          },
          {
            path: ':unitId/recorrido',
            data: { reloadOnParams: true },
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/field/field-capture.component').then(m => m.FieldCaptureComponent),
          },
          // Sin audio: grabar o elegir. Con audio: la espera y la revisión, que se retoman desde Hoy.
          {
            path: ':unitId/audio',
            data: { reloadOnParams: true },
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/field/field-audio.component').then(m => m.FieldAudioComponent),
          },
          {
            path: ':unitId/audio/:extractionId',
            data: { reloadOnParams: true },
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/field/field-audio.component').then(m => m.FieldAudioComponent),
          },
        ],
      },
      {
        path: 'desarrollos',
        loadComponent: () => import('./features/works/works-list.component').then(m => m.WorksListComponent),
      },
      // Cada casa es una obra propia, con su espacio de trabajo. Va antes que el desarrollo
      // para que "casas/:unitId" no lo tome la pestaña Casas.
      {
        path: 'obras/:developmentId/casas/:unitId',
        data: { reloadOnParams: true },
        loadComponent: () => import('./features/works/house-layout.component').then(m => m.HouseLayoutComponent),
        children: [
          {
            path: '',
            loadComponent: () => import('./features/works/house-summary.component').then(m => m.HouseSummaryComponent),
          },
          {
            path: 'etapas',
            loadComponent: () => import('./features/works/work-stages.component').then(m => m.WorkStagesComponent),
          },
          ...scopedWorkRoutes(),
          {
            // Las novedades de la casa: el mismo feed del desarrollo, acotado a ella.
            path: 'novedades',
            loadComponent: () => import('./features/works/work-activity.component').then(m => m.WorkActivityComponent),
          },
          {
            path: 'ficha',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/house-sheet.component').then(m => m.HouseSheetComponent),
          },
          {
            path: 'ficha/planos',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/work-plans.component').then(m => m.WorkPlansComponent),
          },
          {
            path: 'venta',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/house-sales.component').then(m => m.HouseSalesComponent),
          },
          {
            path: 'economico',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/house-economics.component').then(m => m.HouseEconomicsComponent),
          },
        ],
      },
      {
        path: 'obras/:developmentId',
        data: { reloadOnParams: true },
        loadComponent: () => import('./features/works/work-layout.component').then(m => m.WorkLayoutComponent),
        children: [
          {
            path: '',
            loadComponent: () => import('./features/works/work-summary.component').then(m => m.WorkSummaryComponent),
          },
          {
            path: 'novedades',
            canActivate: [ownerGuard],
            loadComponent: () => import('./features/works/work-activity.component').then(m => m.WorkActivityComponent),
          },
          {
            path: 'casas',
            loadComponent: () => import('./features/works/houses.component').then(m => m.HousesComponent),
          },
          {
            path: 'comunes',
            canActivate: [commonWorksGuard],
            loadComponent: () =>
              import('./features/works/common-works-layout.component').then(m => m.CommonWorksLayoutComponent),
            children: [
              { path: '', pathMatch: 'full', redirectTo: 'etapas' },
              {
                path: 'etapas',
                loadComponent: () => import('./features/works/work-stages.component').then(m => m.WorkStagesComponent),
              },
              ...scopedWorkRoutes(),
            ],
          },
          {
            path: 'datos',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () =>
              import('./features/works/development-data.component').then(m => m.DevelopmentDataComponent),
          },
          {
            path: 'economico',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/work-economics.component').then(m => m.WorkEconomicsComponent),
          },
          {
            path: 'economico/reportes',
            canActivate: [ownerGuard],
            loadComponent: () => import('./features/works/work-reports.component').then(m => m.WorkReportsComponent),
          },
          {
            path: 'economico/reportes/:reportId',
            canActivate: [ownerGuard],
            canDeactivate: [leaveGuard],
            loadComponent: () => import('./features/works/work-report-review.component').then(m => m.WorkReportReviewComponent),
          },
          {
            path: 'equipo',
            canActivate: [ownerGuard],
            loadComponent: () => import('./features/works/work-team.component').then(m => m.WorkTeamComponent),
          },
          // Direcciones del modelo anterior, cuando la obra no tenía casas.
          { path: 'avances', redirectTo: 'comunes/historia' },
          { path: 'etapas', redirectTo: 'comunes/etapas' },
          { path: 'recorridos', redirectTo: 'comunes/recorridos' },
          { path: 'unidades', redirectTo: 'casas' },
          { path: 'ficha', redirectTo: 'casas' },
        ],
      },
      {
        path: 'mis-propiedades',
        loadComponent: () => import('./features/property/my-units.component').then(m => m.MyUnitsComponent),
      },
      {
        path: 'mi-propiedad/:unitId',
        loadComponent: () =>
          import('./features/property/property-layout.component').then(m => m.PropertyLayoutComponent),
        children: [
          ...PROPERTY_TABS,
          {
            // Sólo en Mi propiedad: el enlace público no tiene documentos.
            path: 'documentos',
            loadComponent: () =>
              import('./features/property/property-documents.component').then(m => m.PropertyDocumentsComponent),
          },
          {
            path: 'compartir',
            loadComponent: () =>
              import('./features/property/property-share.component').then(m => m.PropertyShareComponent),
          },
        ],
      },
    ],
  },

  // --- Direcciones anteriores: las pantallas ahora viven dentro de la obra ---
  { path: 'visor/:developmentId', redirectTo: 'obras/:developmentId/comunes/recorridos/visor' },
  { path: 'audios/:developmentId', redirectTo: 'obras/:developmentId/comunes/historia/audios' },
  { path: 'reportes/:developmentId', redirectTo: 'obras/:developmentId/economico/reportes' },
  { path: 'panel/:developmentId', redirectTo: 'obras/:developmentId/economico' },

  { path: '**', redirectTo: '' },
];
