package com.obraviva.platform.developments;

import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.developments.DevelopmentTeamDtos.AssignSupervisorRequest;
import com.obraviva.platform.developments.DevelopmentTeamDtos.DevelopmentTeamResponse;
import com.obraviva.platform.developments.DevelopmentTeamDtos.SupervisorScopeRequest;
import com.obraviva.platform.developments.DevelopmentTeamDtos.TeamPerson;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Encargados de cada desarrollo. El dueño decide quién trabaja dónde: en todo el
 * desarrollo o en casas puntuales, y la misma persona puede estar en varias. Un encargado
 * asignado publica avances, audios y recorridos de lo suyo y no ve los desarrollos ajenos.
 *
 * <p>Sumar a alguien a la empresa se hace con {@code OrganizationMemberService}; acá sólo
 * se asigna a quien ya es encargado de la organización.
 */
@Service
public class DevelopmentTeamService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentTeamService.class);

    private final DevelopmentAccessService developmentAccess;
    private final DevelopmentMemberRepository assignmentRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitRepository unitRepository;
    private final OrganizationMembershipService membershipService;
    private final UserDirectoryService userDirectory;

    public DevelopmentTeamService(
            DevelopmentAccessService developmentAccess,
            DevelopmentMemberRepository assignmentRepository,
            UnitSupervisorRepository unitSupervisorRepository,
            UnitRepository unitRepository,
            OrganizationMembershipService membershipService,
            UserDirectoryService userDirectory) {
        this.developmentAccess = developmentAccess;
        this.assignmentRepository = assignmentRepository;
        this.unitSupervisorRepository = unitSupervisorRepository;
        this.unitRepository = unitRepository;
        this.membershipService = membershipService;
        this.userDirectory = userDirectory;
    }

    /** Asignados al desarrollo, con sus casas, y encargados de la empresa que todavía no lo están. */
    @Transactional(readOnly = true)
    public DevelopmentTeamResponse team(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);

        // Persona → desde cuándo está y en qué casas (sin casas: todo el desarrollo).
        Map<UUID, Instant> since = new LinkedHashMap<>();
        Map<UUID, List<UUID>> houses = new LinkedHashMap<>();
        Set<UUID> whole = new LinkedHashSet<>();
        for (DevelopmentMember assignment :
                assignmentRepository.findByDevelopmentIdAndOrganizationIdOrderByCreatedAtAsc(developmentId, organizationId)) {
            since.putIfAbsent(assignment.getUserId(), assignment.getCreatedAt());
            whole.add(assignment.getUserId());
        }
        for (UnitSupervisor assignment :
                unitSupervisorRepository.findByDevelopmentIdAndOrganizationIdOrderByCreatedAtAsc(developmentId, organizationId)) {
            since.merge(assignment.getUserId(), assignment.getCreatedAt(), (a, b) -> a.isBefore(b) ? a : b);
            houses.computeIfAbsent(assignment.getUserId(), id -> new ArrayList<>()).add(assignment.getUnitId());
        }

        List<UUID> supervisors = membershipService.userIdsWithRole(organizationId, OrganizationRole.MEMBER);
        List<UUID> everyone = new ArrayList<>(supervisors);
        since.keySet().stream().filter(id -> !supervisors.contains(id)).forEach(everyone::add);
        Map<UUID, UserSummary> users = userDirectory.findAllById(everyone);

        return new DevelopmentTeamResponse(
                since.entrySet().stream()
                        .map(entry -> person(users, entry.getKey(), entry.getValue(), whole.contains(entry.getKey()),
                                houses.getOrDefault(entry.getKey(), List.of())))
                        .toList(),
                supervisors.stream()
                        .filter(id -> !since.containsKey(id))
                        .map(id -> person(users, id, null, false, List.of()))
                        .toList());
    }

    @Transactional
    public DevelopmentTeamResponse assign(UUID userId, UUID developmentId, AssignSupervisorRequest request) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);

        if (!membershipService.userIdsWithRole(organizationId, OrganizationRole.MEMBER).contains(request.userId())) {
            // El dueño no se asigna (ya ve todo) y alguien de afuera primero se suma a la empresa.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Sólo se asigna a quien es encargado de la empresa");
        }
        if (isAssigned(developmentId, request.userId())) {
            throw alreadyAssigned();
        }
        try {
            save(organizationId, developmentId, request.userId(), request.unitIds(), userId);
        } catch (DataIntegrityViolationException e) {
            throw alreadyAssigned();
        }
        log.info("Encargado {} asignado al desarrollo {} por {}", request.userId(), developmentId, userId);
        return team(userId, developmentId);
    }

    /** Cambia en qué casas trabaja: de todo el desarrollo a casas puntuales, o al revés. */
    @Transactional
    public DevelopmentTeamResponse changeScope(
            UUID userId, UUID developmentId, UUID supervisorId, SupervisorScopeRequest request) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        if (!isAssigned(developmentId, supervisorId)) {
            throw notAssigned();
        }
        removeAssignments(developmentId, supervisorId, organizationId);
        save(organizationId, developmentId, supervisorId, request.unitIds(), userId);
        log.info("Casas del encargado {} en el desarrollo {} cambiadas por {}", supervisorId, developmentId, userId);
        return team(userId, developmentId);
    }

    @Transactional
    public DevelopmentTeamResponse unassign(UUID userId, UUID developmentId, UUID supervisorId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        if (!isAssigned(developmentId, supervisorId)) {
            throw notAssigned();
        }
        removeAssignments(developmentId, supervisorId, organizationId);
        log.info("Encargado {} quitado del desarrollo {} por {}", supervisorId, developmentId, userId);
        return team(userId, developmentId);
    }

    private boolean isAssigned(UUID developmentId, UUID supervisorId) {
        return assignmentRepository.existsByDevelopmentIdAndUserId(developmentId, supervisorId)
                || unitSupervisorRepository.existsByDevelopmentIdAndUserId(developmentId, supervisorId);
    }

    /** Sin casas, todo el desarrollo; si no, una fila por casa, que tiene que ser del desarrollo. */
    private void save(UUID organizationId, UUID developmentId, UUID supervisorId, List<UUID> unitIds, UUID by) {
        if (unitIds == null || unitIds.isEmpty()) {
            assignmentRepository.saveAndFlush(new DevelopmentMember(organizationId, developmentId, supervisorId, by));
            return;
        }
        for (UUID unitId : new LinkedHashSet<>(unitIds)) {
            unitRepository.findByIdAndOrganizationIdIn(unitId, List.of(organizationId))
                    .filter(unit -> unit.getDevelopment().getId().equals(developmentId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esa casa no es de este desarrollo"));
            unitSupervisorRepository.save(new UnitSupervisor(organizationId, developmentId, unitId, supervisorId, by));
        }
        unitSupervisorRepository.flush();
    }

    private void removeAssignments(UUID developmentId, UUID supervisorId, UUID organizationId) {
        assignmentRepository.findByDevelopmentIdAndUserIdAndOrganizationId(developmentId, supervisorId, organizationId)
                .ifPresent(assignmentRepository::delete);
        unitSupervisorRepository.deleteByDevelopmentIdAndUserId(developmentId, supervisorId);
        assignmentRepository.flush();
        unitSupervisorRepository.flush();
    }

    private static TeamPerson person(Map<UUID, UserSummary> users, UUID id, Instant assignedAt,
                                     boolean allHouses, List<UUID> unitIds) {
        UserSummary user = users.get(id);
        return new TeamPerson(id, user == null ? null : user.email(), user == null ? null : user.fullName(),
                assignedAt, allHouses, unitIds);
    }

    private static ResponseStatusException alreadyAssigned() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Esa persona ya está asignada a esta obra");
    }

    private static ResponseStatusException notAssigned() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa persona no está asignada a esta obra");
    }
}
