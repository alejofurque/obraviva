package com.obraviva.platform.developments;

import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * API pública del módulo developments hacia otros módulos: verifica
 * acceso a un desarrollo y devuelve su organización. Los demás módulos
 * no acceden a las entidades de developments.
 *
 * <p><strong>Quién llega a una obra.</strong> El dueño de la organización, a todas. El
 * encargado, a las que tiene asignadas: el desarrollo entero ({@code development_members})
 * o casas puntuales ({@code unit_supervisors}). Es el único lugar donde esa regla está
 * escrita: todo recurso de una obra (etapa, avance, foto, captura, audio) se verifica
 * contra su obra con {@link #requireAccess}, y lo que se carga sobre una casa, además, con
 * {@link #requireWorkOnUnit}.
 */
@Service
public class DevelopmentAccessService {

    private final DevelopmentRepository developmentRepository;
    private final DevelopmentMemberRepository assignmentRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final OrganizationMembershipService membershipService;

    public DevelopmentAccessService(
            DevelopmentRepository developmentRepository,
            DevelopmentMemberRepository assignmentRepository,
            UnitSupervisorRepository unitSupervisorRepository,
            OrganizationMembershipService membershipService) {
        this.developmentRepository = developmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.unitSupervisorRepository = unitSupervisorRepository;
        this.membershipService = membershipService;
    }

    /** Desarrollo visto desde fuera del módulo. */
    public record DevelopmentSummary(UUID id, UUID organizationId, String name, String address) {
    }

    /**
     * Igual que {@link #requireAccess}, y además exige ser dueño de la organización.
     *
     * <p>Sin acceso responde 404, como en todo el sistema. Con acceso pero sin ser dueño
     * responde 403: el encargado ya sabe que el desarrollo existe, y un 404 le mentiría
     * sin proteger nada.
     */
    @Transactional(readOnly = true)
    public UUID requireOwnerAccess(UUID userId, UUID developmentId) {
        return requireOwnedDevelopment(userId, developmentId).organizationId();
    }

    @Transactional(readOnly = true)
    public DevelopmentSummary requireOwnedDevelopment(UUID userId, UUID developmentId) {
        DevelopmentSummary development = requireAccessibleDevelopment(userId, developmentId);
        requireOwnerOf(userId, development.organizationId());
        return development;
    }

    /**
     * Para recursos ya localizados dentro del alcance del usuario: un gasto, un
     * reporte, una etapa. Quien llama garantiza el acceso; esto sólo verifica el rol.
     */
    public void requireOwnerOf(UUID userId, UUID organizationId) {
        if (!membershipService.isOwnerOf(userId, organizationId)) {
            throw ownerOnly();
        }
    }

    public static ResponseStatusException ownerOnly() {
        return OrganizationMembershipService.ownerOnly();
    }

    /** Devuelve el organizationId del desarrollo, o 404 si el usuario no tiene acceso. */
    @Transactional(readOnly = true)
    public UUID requireAccess(UUID userId, UUID developmentId) {
        return requireAccessibleDevelopment(userId, developmentId).organizationId();
    }

    /**
     * Igual que {@link #requireAccess}, pero devuelve además los datos descriptivos
     * del desarrollo. Existe porque el reporte de período necesita nombrar la obra
     * en su texto, y ningún módulo accede a la entidad Development.
     */
    @Transactional(readOnly = true)
    public DevelopmentSummary requireAccessibleDevelopment(UUID userId, UUID developmentId) {
        Development development = requireVisible(userId, developmentId);
        return new DevelopmentSummary(
                development.getId(),
                development.getOrganizationId(),
                development.getName(),
                development.getAddress());
    }

    /** Si el usuario llega a la obra, sin lanzar: para caminos que prueban más de un acceso. */
    @Transactional(readOnly = true)
    public boolean canAccess(UUID userId, UUID developmentId, UUID organizationId) {
        return membershipService.isOwnerOf(userId, organizationId)
                || (membershipService.isMemberOf(userId, organizationId)
                        && (assignmentRepository.existsByDevelopmentIdAndUserId(developmentId, userId)
                                || unitSupervisorRepository.existsByDevelopmentIdAndUserId(developmentId, userId)));
    }

    /**
     * Casas en las que el usuario carga dentro de un desarrollo al que ya llega. Vacío
     * significa todas: es dueño o encargado del desarrollo entero.
     */
    @Transactional(readOnly = true)
    public Optional<Set<UUID>> houseScope(UUID userId, UUID developmentId, UUID organizationId) {
        if (membershipService.isOwnerOf(userId, organizationId)
                || assignmentRepository.existsByDevelopmentIdAndUserId(developmentId, userId)) {
            return Optional.empty();
        }
        Set<UUID> houses = new LinkedHashSet<>();
        unitSupervisorRepository.findByDevelopmentIdAndUserId(developmentId, userId)
                .forEach(assignment -> houses.add(assignment.getUnitId()));
        return Optional.of(houses);
    }

    /** Si el usuario puede cargar sobre esa casa; {@code unitId} nulo son las obras comunes. */
    @Transactional(readOnly = true)
    public boolean canWorkOnUnit(UUID userId, UUID developmentId, UUID organizationId, UUID unitId) {
        return houseScope(userId, developmentId, organizationId)
                .map(houses -> unitId != null && houses.contains(unitId))
                .orElse(true);
    }

    /**
     * Qué casas puede leer el usuario en un desarrollo al que ya llega: las suyas y las
     * obras comunes ({@code unitId} nulo), que un encargado de casas puntuales ve aunque no
     * las cargue. Se arma una vez por pedido para filtrar listados enteros.
     */
    @Transactional(readOnly = true)
    public Predicate<UUID> readableUnits(UUID userId, UUID developmentId, UUID organizationId) {
        return houseScope(userId, developmentId, organizationId)
                .<Predicate<UUID>>map(houses -> unitId -> unitId == null || houses.contains(unitId))
                .orElse(unitId -> true);
    }

    /**
     * Qué casas puede cargar: como {@link #canWorkOnUnit}, para filtrar listados de lo que
     * es trabajo (audios por revisar), donde las obras comunes no le tocan.
     */
    @Transactional(readOnly = true)
    public Predicate<UUID> workableUnits(UUID userId, UUID developmentId, UUID organizationId) {
        return houseScope(userId, developmentId, organizationId)
                .<Predicate<UUID>>map(houses -> unitId -> unitId != null && houses.contains(unitId))
                .orElse(unitId -> true);
    }

    /**
     * Para lo que se carga sobre una casa (avance, etapa, foto, audio, recorrido) en un
     * desarrollo al que el usuario ya llega. Un encargado de casas puntuales recibe 403 en
     * las demás y en las obras comunes: sabe que existen, no le toca cargarlas.
     */
    @Transactional(readOnly = true)
    public void requireWorkOnUnit(UUID userId, UUID developmentId, UUID organizationId, UUID unitId) {
        if (!canWorkOnUnit(userId, developmentId, organizationId, unitId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, unitId == null
                    ? "Las obras comunes las carga un encargado de todo el desarrollo"
                    : "No estás asignado a esta casa");
        }
    }

    /**
     * Obras que el usuario ve desde su organización: todas las de las organizaciones de
     * las que es dueño, más las asignadas en las demás.
     */
    @Transactional(readOnly = true)
    public Set<UUID> visibleDevelopmentIds(UUID userId) {
        Set<UUID> ids = new LinkedHashSet<>();
        List<UUID> owned = membershipService.ownedOrganizationIdsOf(userId);
        if (!owned.isEmpty()) {
            developmentRepository.findByOrganizationIdIn(owned).forEach(development -> ids.add(development.getId()));
        }
        List<UUID> organizations = membershipService.organizationIdsOf(userId);
        if (!organizations.isEmpty()) {
            assignmentRepository.findByUserIdAndOrganizationIdIn(userId, organizations)
                    .forEach(assignment -> ids.add(assignment.getDevelopmentId()));
            unitSupervisorRepository.findByUserIdAndOrganizationIdIn(userId, organizations)
                    .forEach(assignment -> ids.add(assignment.getDevelopmentId()));
        }
        return ids;
    }

    /** La entidad, para el propio módulo: 404 tanto si no existe como si no la ve. */
    Development requireVisible(UUID userId, UUID developmentId) {
        return developmentRepository
                .findByIdAndOrganizationIdIn(developmentId, membershipService.organizationIdsOf(userId))
                .filter(development -> canAccess(userId, development.getId(), development.getOrganizationId()))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Desarrollo no encontrado"));
    }
}
