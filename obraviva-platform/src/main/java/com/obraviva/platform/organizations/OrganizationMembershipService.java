package com.obraviva.platform.organizations;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * API pública del módulo organizations hacia otros módulos:
 * responde a qué organizaciones pertenece un usuario. Los demás
 * módulos usan esto para acotar sus consultas, sin conocer las
 * entidades internas de organizations.
 */
@Service
public class OrganizationMembershipService {

    private final OrganizationMemberRepository memberRepository;

    public OrganizationMembershipService(OrganizationMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public List<UUID> organizationIdsOf(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(member -> member.getOrganization().getId())
                .toList();
    }

    public boolean isMemberOf(UUID userId, UUID organizationId) {
        return organizationIdsOf(userId).contains(organizationId);
    }

    /**
     * Organizaciones donde el usuario es dueño. Acota las consultas de información que
     * el encargado no ve: una bandeja general, por ejemplo, le queda vacía.
     */
    public List<UUID> ownedOrganizationIdsOf(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .filter(member -> member.getRole() == OrganizationRole.OWNER)
                .map(member -> member.getOrganization().getId())
                .toList();
    }

    public boolean isOwnerOf(UUID userId, UUID organizationId) {
        return ownedOrganizationIdsOf(userId).contains(organizationId);
    }

    /** Usuarios de la organización con un rol, en el orden en que se sumaron. */
    public List<UUID> userIdsWithRole(UUID organizationId, OrganizationRole role) {
        return memberRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .filter(member -> member.getRole() == role)
                .map(OrganizationMember::getUserId)
                .toList();
    }

    /** Miembro de una organización visto desde fuera del módulo. */
    public record MemberInfo(UUID memberId, UUID userId, OrganizationRole role) {
    }

    /** Busca un miembro dentro de su organización; vacío si no pertenece a ella. */
    public Optional<MemberInfo> findMember(UUID organizationId, UUID memberId) {
        return memberRepository.findByIdAndOrganizationId(memberId, organizationId)
                .map(member -> new MemberInfo(member.getId(), member.getUserId(), member.getRole()));
    }

    /**
     * La respuesta de toda restricción por rol. Vive acá, y no en cada módulo, para que
     * el mensaje que ve el encargado sea el mismo en cualquier pantalla.
     */
    public static ResponseStatusException ownerOnly() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Esta sección está reservada al responsable del desarrollo");
    }

    /**
     * Rol del usuario en cada organización a la que pertenece. La base garantiza una
     * sola membresía por par organización-usuario.
     */
    public Map<UUID, OrganizationRole> rolesOf(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        member -> member.getOrganization().getId(),
                        OrganizationMember::getRole));
    }
}