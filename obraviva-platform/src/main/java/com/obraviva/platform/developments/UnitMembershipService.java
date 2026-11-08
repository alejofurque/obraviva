package com.obraviva.platform.developments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública del módulo developments para consultar el vínculo comprador-unidad.
 *
 * <p>Es el equivalente de {@code OrganizationMembershipService} en el segundo camino
 * de acceso del sistema.
 */
@Service
public class UnitMembershipService {

    private final UnitMemberRepository memberRepository;

    public UnitMembershipService(UnitMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /** Unidades que le pertenecen al usuario. Vacío para quien no compró nada. */
    @Transactional(readOnly = true)
    public List<UUID> unitIdsOf(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(UnitMember::getUnitId)
                .toList();
    }

    /** Organizaciones de las obras donde el usuario tiene unidades. */
    @Transactional(readOnly = true)
    public List<UUID> organizationIdsOfMemberships(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(UnitMember::getOrganizationId)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isMemberOf(UUID userId, UUID unitId) {
        return memberRepository.existsByUnitIdAndUserId(unitId, userId);
    }

    /**
     * Organización de la obra a la que pertenece la unidad, según el vínculo.
     *
     * <p>El comprador no es miembro de esa organización: el vínculo es lo único que la
     * aporta, y sin ella no habría con qué acotar las consultas posteriores.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> organizationOf(UUID userId, UUID unitId) {
        return memberRepository.findByUserId(userId).stream()
                .filter(member -> member.getUnitId().equals(unitId))
                .map(UnitMember::getOrganizationId)
                .findFirst();
    }
}