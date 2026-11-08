package com.obraviva.platform.progress;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.obraviva.platform.developments.StageStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avance nuevo. {@code unitId} es la casa; sin casa, es una novedad de las obras comunes
 * del desarrollo. La etapa, si viene, tiene que ser de esa misma casa.
 *
 * <p>{@code photoIds} son fotos ya subidas y todavía sin confirmar, que se confirman con
 * el avance; {@code photoSectors}, opcional, dice el lugar de la casa de cada una (las que
 * no figuran quedan en "General"). {@code stageUpdate} cambia el avance y el estado de la etapa en la misma
 * operación: o sale todo junto, o no sale nada. Los dos son opcionales, y sin ellos el
 * cuerpo es el de siempre.
 */
public record CreateProgressEntryRequest(
        UUID stageId,
        @NotNull LocalDate entryDate,
        @NotBlank @Size(max = 2000) String description,
        UUID unitId,
        @Size(max = 10) List<UUID> photoIds,
        @Valid StageUpdate stageUpdate,
        @Size(max = 10) Map<UUID, @Size(max = 80) String> photoSectors) {

    public CreateProgressEntryRequest(UUID stageId, LocalDate entryDate, String description) {
        this(stageId, entryDate, description, null, null, null, null);
    }

    public CreateProgressEntryRequest(UUID stageId, LocalDate entryDate, String description, UUID unitId) {
        this(stageId, entryDate, description, unitId, null, null, null);
    }

    public CreateProgressEntryRequest(
            UUID stageId, LocalDate entryDate, String description, UUID unitId, List<UUID> photoIds, StageUpdate stageUpdate) {
        this(stageId, entryDate, description, unitId, photoIds, stageUpdate, null);
    }

    /**
     * Lo único que un avance cambia de su etapa: el porcentaje y el estado.
     *
     * <p>Rechaza cualquier otro campo, a diferencia del resto del cuerpo. Spring configura
     * Jackson para ignorar los desconocidos, y un {@code budgetAmount} que se manda y no
     * hace nada es peor que un error: quien lo mandó cree que cambió el presupuesto. El
     * presupuesto y el peso son del dueño y se cambian en el plan de la etapa.
     */
    public record StageUpdate(@Min(0) @Max(100) Integer progressPercent, StageStatus status) {

        @JsonAnySetter
        void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException(
                    "El avance sólo cambia el porcentaje y el estado de la etapa, no " + field);
        }
    }
}
