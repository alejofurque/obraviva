package com.obraviva.platform.developments;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DevelopmentTeamDtos {

    private DevelopmentTeamDtos() {
    }

    /** Sin casas, o con la lista vacía, el encargado queda en todo el desarrollo. */
    public record AssignSupervisorRequest(@NotNull UUID userId, @Size(max = 500) List<UUID> unitIds) {

        public AssignSupervisorRequest(UUID userId) {
            this(userId, null);
        }
    }

    /** Cambia en qué casas trabaja un encargado ya asignado. Vacío: todo el desarrollo. */
    public record SupervisorScopeRequest(@Size(max = 500) List<UUID> unitIds) {
    }

    /**
     * Persona del equipo. {@code assignedAt} es nulo para quien todavía no está en la obra.
     * {@code allHouses} dice si trabaja en todo el desarrollo; si no, {@code unitIds} son sus
     * casas.
     */
    public record TeamPerson(UUID userId, String email, String fullName, Instant assignedAt,
                             boolean allHouses, List<UUID> unitIds) {

        public TeamPerson(UUID userId, String email, String fullName, Instant assignedAt) {
            this(userId, email, fullName, assignedAt, assignedAt != null, List.of());
        }
    }

    public record DevelopmentTeamResponse(List<TeamPerson> assigned, List<TeamPerson> available) {
    }
}
