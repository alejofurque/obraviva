package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitCatalogService.UnitSummary;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Autorización sobre una unidad.
 *
 * <p>Resuelve los <strong>dos caminos de acceso</strong> del sistema y es el único
 * lugar donde esa dualidad está escrita. Se llega a una unidad por pertenecer a la
 * organización dueña de la obra, o por haberla comprado. Un mismo usuario puede ser
 * del equipo en un desarrollo y comprador en otro, tal como exige el documento
 * maestro.
 *
 * <p>Los dos caminos se prueban en orden y ambos quedan acotados en la firma del
 * repositorio que consultan: ni uno solo busca sin acotar.
 */
@Service
public class UnitAccessService {

    private final UnitCatalogService unitCatalog;
    private final UnitMembershipService unitMembership;
    private final OrganizationMembershipService organizationMembership;
    private final DevelopmentAccessService developmentAccess;

    public UnitAccessService(
            UnitCatalogService unitCatalog,
            UnitMembershipService unitMembership,
            OrganizationMembershipService organizationMembership,
            DevelopmentAccessService developmentAccess) {
        this.unitCatalog = unitCatalog;
        this.unitMembership = unitMembership;
        this.organizationMembership = organizationMembership;
        this.developmentAccess = developmentAccess;
    }

    /** Cómo llegó el usuario a la unidad. Cambia lo que puede hacer con ella. */
    public enum AccessPath {
        /** Pertenece a la organización dueña de la obra. */
        ORGANIZATION,
        /** Compró la unidad. */
        UNIT_MEMBER
    }

    public record UnitAccess(UnitSummary unit, AccessPath path) {
        public boolean isBuyer() {
            return path == AccessPath.UNIT_MEMBER;
        }
    }

    /**
     * @throws ResponseStatusException 404 si el usuario no llega por ninguno de los
     *         dos caminos. Nunca 403: no se confirma que la unidad exista.
     */
    @Transactional(readOnly = true)
    public UnitAccess requireAccess(UUID userId, UUID unitId) {
        for (UUID organizationId : organizationMembership.organizationIdsOf(userId)) {
            Optional<UnitSummary> found = unitCatalog.findInOrganization(unitId, organizationId)
                    // El encargado llega a la unidad sólo si tiene asignada su obra y, si es
                    // de casas puntuales, esa casa: las demás no existen para él (404).
                    .filter(unit -> developmentAccess.canAccess(userId, unit.developmentId(), organizationId)
                            && developmentAccess.canWorkOnUnit(userId, unit.developmentId(), organizationId, unit.id()));
            if (found.isPresent()) {
                return new UnitAccess(found.get(), AccessPath.ORGANIZATION);
            }
        }

        if (unitMembership.isMemberOf(userId, unitId)) {
            // El comprador no pertenece a la organización, así que la unidad se busca
            // contra la organización que el propio vínculo aporta.
            UnitSummary unit = unitCatalog
                    .findInOrganization(unitId, organizationOfMembership(userId, unitId))
                    .orElseThrow(UnitAccessService::notFound);
            return new UnitAccess(unit, AccessPath.UNIT_MEMBER);
        }

        throw notFound();
    }

    /** Unidades del usuario como comprador, con los datos de su obra. */
    @Transactional(readOnly = true)
    public List<UnitAccess> ownedUnits(UUID userId) {
        return unitMembership.unitIdsOf(userId).stream()
                .map(unitId -> unitCatalog
                        .findInOrganization(unitId, organizationOfMembership(userId, unitId))
                        .map(unit -> new UnitAccess(unit, AccessPath.UNIT_MEMBER)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private UUID organizationOfMembership(UUID userId, UUID unitId) {
        return unitMembership.organizationOf(userId, unitId).orElseThrow(UnitAccessService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada");
    }
}