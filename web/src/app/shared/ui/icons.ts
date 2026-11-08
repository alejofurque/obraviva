import { EnvironmentProviders, inject, provideEnvironmentInitializer } from '@angular/core';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';

import add from '@material-symbols/svg-400/outlined/add.svg';
import apartment from '@material-symbols/svg-400/outlined/apartment.svg';
import check from '@material-symbols/svg-400/outlined/check.svg';
import checkCircle from '@material-symbols/svg-400/outlined/check_circle.svg';
import chevronLeft from '@material-symbols/svg-400/outlined/chevron_left.svg';
import chevronRight from '@material-symbols/svg-400/outlined/chevron_right.svg';
import close from '@material-symbols/svg-400/outlined/close.svg';
import description from '@material-symbols/svg-400/outlined/description.svg';
import error from '@material-symbols/svg-400/outlined/error.svg';
import group from '@material-symbols/svg-400/outlined/group.svg';
import history from '@material-symbols/svg-400/outlined/history.svg';
import iosShare from '@material-symbols/svg-400/outlined/ios_share.svg';
import home from '@material-symbols/svg-400/outlined/home.svg';
import lightMode from '@material-symbols/svg-400/outlined/light_mode.svg';
import list from '@material-symbols/svg-400/outlined/list.svg';
import logout from '@material-symbols/svg-400/outlined/logout.svg';
import menu from '@material-symbols/svg-400/outlined/menu.svg';
import mic from '@material-symbols/svg-400/outlined/mic.svg';
import monitoring from '@material-symbols/svg-400/outlined/monitoring.svg';
import moreVert from '@material-symbols/svg-400/outlined/more_vert.svg';
import pause from '@material-symbols/svg-400/outlined/pause.svg';
import playArrow from '@material-symbols/svg-400/outlined/play_arrow.svg';
import photoCamera from '@material-symbols/svg-400/outlined/photo_camera.svg';
import radioButtonUnchecked from '@material-symbols/svg-400/outlined/radio_button_unchecked.svg';
import refresh from '@material-symbols/svg-400/outlined/refresh.svg';
import schedule from '@material-symbols/svg-400/outlined/schedule.svg';
import stop from '@material-symbols/svg-400/outlined/stop.svg';
import upload from '@material-symbols/svg-400/outlined/upload.svg';
import videocam from '@material-symbols/svg-400/outlined/videocam.svg';
import viewInAr from '@material-symbols/svg-400/outlined/view_in_ar.svg';
import warning from '@material-symbols/svg-400/outlined/warning.svg';

/**
 * Íconos de la aplicación, empaquetados como SVG.
 *
 * <p>No se usa la fuente de íconos: trae miles de glifos y pesa casi 4 MB, un costo
 * inaceptable para la vista pública que se abre desde un teléfono. Cada SVG pesa
 * menos de 1 KB y sólo entra al paquete el que se importa acá.
 *
 * <p>Se registran con el nombre de Material Symbols, así que un ícono nuevo se suma
 * con su import y una línea en el mapa, y se usa con {@code <mat-icon svgIcon="...">}.
 */
const ICONS: Record<string, string> = {
  add,
  apartment,
  check,
  check_circle: checkCircle,
  chevron_left: chevronLeft,
  chevron_right: chevronRight,
  close,
  description,
  error,
  group,
  history,
  ios_share: iosShare,
  home,
  light_mode: lightMode,
  list,
  logout,
  menu,
  mic,
  monitoring,
  more_vert: moreVert,
  pause,
  photo_camera: photoCamera,
  play_arrow: playArrow,
  radio_button_unchecked: radioButtonUnchecked,
  refresh,
  schedule,
  stop,
  upload,
  videocam,
  view_in_ar: viewInAr,
  warning,
};

export function provideAppIcons(): EnvironmentProviders {
  return provideEnvironmentInitializer(() => {
    const registry = inject(MatIconRegistry);
    const sanitizer = inject(DomSanitizer);
    for (const [name, svg] of Object.entries(ICONS)) {
      // Los SVG vienen del paquete oficial, no de datos del usuario: confiar en ellos
      // es seguro, y es lo que exige el registro para aceptar un literal.
      registry.addSvgIconLiteral(name, sanitizer.bypassSecurityTrustHtml(svg));
    }
  });
}
