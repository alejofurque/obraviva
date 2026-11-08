import { ChangeDetectionStrategy, Component, DestroyRef, Injectable, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { CanDeactivateFn } from '@angular/router';
import { Observable, map, of } from 'rxjs';

/** El texto de siempre, para lo que se escribió o eligió y todavía no se guardó. */
export const UNSAVED_CHANGES = 'Tenés cambios sin guardar. ¿Salir igual?';

/** Para una subida en curso: salir la corta. */
export function uploadCutWarning(what: string): string {
  return `Se está subiendo ${what}. Si salís, la subida se corta. ¿Salir igual?`;
}

/**
 * Una pantalla con trabajo sin guardar. `leaveWarning()` devuelve el aviso para mostrar al
 * salir, o nulo si no hay nada que perder. Lo usan la guarda de la ruta, el cierre de la
 * pestaña y cualquier cambio de pantalla que la pantalla haga por su cuenta.
 */
export interface LeaveGuarded {
  leaveWarning(): string | null;
}

@Component({
  selector: 'app-leave-dialog',
  imports: [MatDialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p mat-dialog-content class="message">{{ message }}</p>
    <div mat-dialog-actions class="actions">
      <button type="button" class="ov-btn ov-btn--secondary" (click)="close(false)" cdkFocusInitial>Quedarme</button>
      <button type="button" class="ov-btn ov-btn--primary" (click)="close(true)">Salir igual</button>
    </div>
  `,
  styles: `
    .message {
      margin: 0;
      padding-top: var(--ov-space-5);
      color: var(--ov-ink);
      font-size: var(--ov-text-md);
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--ov-space-3);
      padding: var(--ov-space-4) var(--ov-space-5) var(--ov-space-5);
    }

    .actions .ov-btn {
      min-height: 3rem;
    }
  `,
})
export class LeaveDialogComponent {
  protected readonly message = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LeaveDialogComponent, boolean>);

  protected close(leave: boolean): void {
    this.ref.close(leave);
  }
}

/** Pregunta antes de dejar algo sin guardar, con el diálogo de la app (no `confirm()`). */
@Injectable({ providedIn: 'root' })
export class LeaveConfirmation {
  private readonly dialog = inject(MatDialog);

  /** Verdadero si se puede salir: no había nada que perder o la persona eligió "Salir igual". */
  confirm(warning: string | null): Observable<boolean> {
    if (!warning) {
      return of(true);
    }
    return this.dialog
      .open<LeaveDialogComponent, string, boolean>(LeaveDialogComponent, { data: warning, autoFocus: 'dialog', maxWidth: '28rem' })
      .afterClosed()
      .pipe(map(leave => leave === true));
  }
}

/** En las rutas de pantallas con trabajo sin guardar. */
export const leaveGuard: CanDeactivateFn<LeaveGuarded> = component =>
  component?.leaveWarning ? inject(LeaveConfirmation).confirm(component.leaveWarning()) : true;

/**
 * Cerrar o recargar la pestaña con algo sin guardar: el navegador pregunta con su propio
 * aviso (no deja poner texto). Se llama en el constructor de la pantalla.
 */
export function warnOnUnload(warning: () => string | null): void {
  const listener = (event: BeforeUnloadEvent) => {
    if (warning()) {
      event.preventDefault();
      // Los navegadores viejos piden también returnValue.
      event.returnValue = '';
    }
  };
  window.addEventListener('beforeunload', listener);
  inject(DestroyRef).onDestroy(() => window.removeEventListener('beforeunload', listener));
}
