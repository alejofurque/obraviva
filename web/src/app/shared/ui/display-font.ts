/** La hoja de Fraunces (angular.json, inject: false), servida local como `buyer-fonts.css`. */
export const DISPLAY_FONT_STYLESHEET = 'buyer-fonts.css';

/**
 * Agrega una vez la hoja de Fraunces, la serif de los títulos (`.ov-display`). No va en los
 * estilos globales para que el resto de la aplicación no la descargue: la piden sólo las
 * pantallas que la usan, la vista del comprador y el ingreso. Es local, como IBM Plex: la
 * defensa no depende de internet ni de Google Fonts.
 */
export function loadDisplayFont(document: Document): void {
  if (document.querySelector(`link[href="${DISPLAY_FONT_STYLESHEET}"]`)) {
    return;
  }
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = DISPLAY_FONT_STYLESHEET;
  document.head.appendChild(link);
}
