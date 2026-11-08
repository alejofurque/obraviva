import { inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Stage } from '../../core/api/api.models';
import { StagesService } from '../../core/api/stages.service';
import { FieldHouseStore } from '../field/field-house.store';
import { HouseStore } from './house.store';
import { WorkStore } from './work.store';

/**
 * Sobre qué trabaja una pantalla: una casa o las obras comunes del desarrollo.
 *
 * <p>Etapas, Historia, Recorridos, audios, proyecto y realidad y Económico son las mismas
 * pantallas en los dos lugares. Dentro de una casa hay un {@link HouseStore}; fuera, no.
 */
export interface WorkScope {
  /** Casa de la pantalla; nulo en las obras comunes. */
  unitId(): string | null;
  isHouse(): boolean;
  developmentId(): string;
  /** Si un elemento (avance, foto, recorrido, gasto) es de este lugar. */
  owns(itemUnitId: string | null | undefined): boolean;
  /** Ruta dentro del lugar: la casa o las obras comunes. */
  link(...path: string[]): string[];
  stages(): Observable<Stage[]>;
  /** Refresca encabezados (el avance del desarrollo y el de la casa) y deja viejo a Hoy. */
  refresh(): void;
}

/** Se llama en el contexto de inyección de un componente, como {@code inject}. */
export function injectWorkScope(): WorkScope {
  const work = inject(WorkStore);
  const house = inject(HouseStore, { optional: true });
  const stagesService = inject(StagesService);
  const field = inject(FieldHouseStore);

  const unitId = () => (house ? house.unitId() : null);
  const developmentId = () => work.development()?.id ?? '';

  return {
    unitId,
    isHouse: () => house !== null,
    developmentId,
    owns: itemUnitId => (itemUnitId ?? null) === unitId(),
    link: (...path) => house
      ? ['/obras', developmentId(), 'casas', unitId() ?? '', ...path]
      : ['/obras', developmentId(), 'comunes', ...path],
    stages: () => house ? stagesService.listByUnit(unitId() ?? '') : stagesService.listByDevelopment(developmentId()),
    refresh: () => {
      work.reload();
      house?.reload();
      field.invalidate();
    },
  };
}
