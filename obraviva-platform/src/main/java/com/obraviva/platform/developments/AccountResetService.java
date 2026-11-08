package com.obraviva.platform.developments;

import com.obraviva.platform.auth.AccountProvisioningService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationMembershipService.MemberInfo;
import com.obraviva.platform.organizations.OrganizationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Reinicio de contraseña de encargados y compradores por el dueño del desarrollo.
 *
 * <p>Una cuenta es de la persona, no de la organización: la misma puede ser compradora
 * en una obra y dueña de otra empresa. Si cualquier dueño pudiera reiniciarla, podría
 * quedarse con el acceso a una organización ajena. Por eso sólo se reinicia una cuenta
 * que existe <strong>únicamente</strong> dentro de la organización de quien lo pide;
 * si la persona participa en otra, cambia su contraseña ella misma.
 *
 * <p>Vive en este módulo porque es el único que ve las dos membresías, la de
 * organización y la de unidad.
 */
@Service
public class AccountResetService {

    private static final Logger log = LoggerFactory.getLogger(AccountResetService.class);

    private final OrganizationMembershipService organizationMembership;
    private final UnitMembershipService unitMembership;
    private final UnitMemberRepository unitMemberRepository;
    private final AccountProvisioningService accountProvisioning;

    public AccountResetService(
            OrganizationMembershipService organizationMembership,
            UnitMembershipService unitMembership,
            UnitMemberRepository unitMemberRepository,
            AccountProvisioningService accountProvisioning) {
        this.organizationMembership = organizationMembership;
        this.unitMembership = unitMembership;
        this.unitMemberRepository = unitMemberRepository;
        this.accountProvisioning = accountProvisioning;
    }

    public record ResetPasswordResponse(String initialPassword) {
    }

    @Transactional
    public ResetPasswordResponse resetOrganizationMember(
            UUID ownerId, UUID organizationId, UUID memberId) {
        requireOwner(ownerId, organizationId);

        MemberInfo member = organizationMembership.findMember(organizationId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado"));
        if (member.role() == OrganizationRole.OWNER) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La contraseña del dueño la cambia el propio dueño");
        }

        return reset(ownerId, member.userId(), organizationId);
    }

    @Transactional
    public ResetPasswordResponse resetUnitMember(UUID ownerId, UUID unitMemberId) {
        return resetUnitMember(ownerId, unitMemberId, null);
    }

    /**
     * Nueva contraseña para un comprador: la que eligió el dueño o, nula, una generada. Sólo
     * por el camino de la organización: quien no la integra no encuentra el vínculo (404) y
     * un encargado no es dueño (403). Las sesiones abiertas del comprador se cierran.
     */
    @Transactional
    public ResetPasswordResponse resetUnitMember(UUID ownerId, UUID unitMemberId, String chosenPassword) {
        UnitMember member = unitMemberRepository
                .findByIdAndOrganizationIdIn(unitMemberId, organizationMembership.organizationIdsOf(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vínculo no encontrado"));
        if (!organizationMembership.isOwnerOf(ownerId, member.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }

        return reset(ownerId, member.getUserId(), member.getOrganizationId(), chosenPassword);
    }

    private ResetPasswordResponse reset(UUID ownerId, UUID targetUserId, UUID organizationId) {
        return reset(ownerId, targetUserId, organizationId, null);
    }

    private ResetPasswordResponse reset(UUID ownerId, UUID targetUserId, UUID organizationId, String chosenPassword) {
        if (targetUserId.equals(ownerId) || !belongsOnlyTo(targetUserId, organizationId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta cuenta también se usa fuera de esta organización. "
                            + "La persona tiene que cambiar su contraseña desde su perfil.");
        }
        // Con una contraseña elegida devuelve nulo: no vuelve en la respuesta.
        String initialPassword = accountProvisioning.changePassword(targetUserId, chosenPassword);
        log.info("Contraseña de la cuenta {} cambiada por {} en la organización {}",
                targetUserId, ownerId, organizationId);
        return new ResetPasswordResponse(initialPassword);
    }

    /**
     * La cuenta no tiene nada fuera de la organización: a lo sumo es encargada en ella,
     * y todas sus unidades son de obras de ella.
     */
    private boolean belongsOnlyTo(UUID userId, UUID organizationId) {
        Map<UUID, OrganizationRole> roles = organizationMembership.rolesOf(userId);
        boolean organizationsOk = roles.entrySet().stream()
                .allMatch(entry -> entry.getKey().equals(organizationId)
                        && entry.getValue() == OrganizationRole.MEMBER);
        boolean unitsOk = unitMembership.organizationIdsOfMemberships(userId).stream()
                .allMatch(organizationId::equals);
        return organizationsOk && unitsOk;
    }

    private void requireOwner(UUID userId, UUID organizationId) {
        OrganizationRole role = organizationMembership.rolesOf(userId).get(organizationId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada");
        }
        if (role != OrganizationRole.OWNER) {
            throw OrganizationMembershipService.ownerOnly();
        }
    }
}
