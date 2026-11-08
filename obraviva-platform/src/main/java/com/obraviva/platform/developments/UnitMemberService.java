package com.obraviva.platform.developments;

import com.obraviva.platform.auth.AccountProvisioningService;
import com.obraviva.platform.auth.AccountProvisioningService.ProvisionedAccount;
import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.developments.UnitAccessService.UnitAccess;
import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.developments.UnitMemberDtos.AddedUnitMemberResponse;
import com.obraviva.platform.developments.UnitMemberDtos.UnitMemberResponse;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alta y baja de compradores en una unidad, desde la sesión del desarrollista.
 *
 * <p>Sólo quien llega por la organización puede administrar miembros: un comprador ve
 * su unidad, no decide quién más la tiene.
 */
@Service
public class UnitMemberService {

    private static final Logger log = LoggerFactory.getLogger(UnitMemberService.class);

    private final UnitMemberRepository memberRepository;
    private final UnitRepository unitRepository;
    private final UnitAccessService unitAccess;
    private final UserDirectoryService userDirectory;
    private final OrganizationMembershipService organizationMembership;
    private final AccountProvisioningService accountProvisioning;

    public UnitMemberService(
            UnitMemberRepository memberRepository,
            UnitRepository unitRepository,
            UnitAccessService unitAccess,
            UserDirectoryService userDirectory,
            OrganizationMembershipService organizationMembership,
            AccountProvisioningService accountProvisioning) {
        this.memberRepository = memberRepository;
        this.unitRepository = unitRepository;
        this.unitAccess = unitAccess;
        this.userDirectory = userDirectory;
        this.organizationMembership = organizationMembership;
        this.accountProvisioning = accountProvisioning;
    }

    @Transactional
    public AddedUnitMemberResponse add(UUID userId, UUID unitId, AddUnitMemberRequest request) {
        UnitAccess access = requireOrganizationAccess(userId, unitId);

        // La contraseña elegida sólo cuenta si la cuenta se crea acá; con cuenta existente se ignora.
        ProvisionedAccount account = accountProvisioning
                .findOrCreate(request.email(), request.fullName(), request.password())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No hay ninguna cuenta con ese correo. Indicá el nombre para crearla."));
        UserSummary buyer = account.user();

        if (memberRepository.existsByUnitIdAndUserId(unitId, buyer.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Esa persona ya está vinculada a la casa");
        }

        UnitMember member = memberRepository.save(new UnitMember(
                access.unit().organizationId(), unitId, buyer.id(), userId));
        unitRepository.findByIdAndOrganizationIdIn(unitId, List.of(access.unit().organizationId()))
                .ifPresent(Unit::markSoldIfAvailable);

        log.info("Comprador {} vinculado a la unidad {} por {} (cuenta nueva: {})",
                buyer.id(), unitId, userId, account.created());

        return new AddedUnitMemberResponse(
                member.getId(), unitId, buyer.id(), buyer.email(), buyer.fullName(),
                member.getCreatedAt(), account.created(), account.initialPassword());
    }

    @Transactional(readOnly = true)
    public List<UnitMemberResponse> listByUnit(UUID userId, UUID unitId) {
        requireOrganizationAccess(userId, unitId);

        List<UnitMember> members = memberRepository
                .findByUnitIdAndOrganizationIdInOrderByCreatedAtAsc(
                        unitId, organizationMembership.organizationIdsOf(userId));

        Map<UUID, UserSummary> users = userDirectory.findAllById(
                members.stream().map(UnitMember::getUserId).toList());

        return members.stream()
                .map(member -> {
                    UserSummary user = users.get(member.getUserId());
                    return new UnitMemberResponse(
                            member.getId(),
                            member.getUnitId(),
                            member.getUserId(),
                            user == null ? null : user.email(),
                            user == null ? null : user.fullName(),
                            member.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public void remove(UUID userId, UUID memberId) {
        UnitMember member = memberRepository
                .findByIdAndOrganizationIdIn(
                        memberId, organizationMembership.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Vínculo no encontrado"));
        if (!organizationMembership.isOwnerOf(userId, member.getOrganizationId())) {
            throw DevelopmentAccessService.ownerOnly();
        }

        memberRepository.delete(member);
        log.info("Vínculo {} eliminado por {}", memberId, userId);
    }

    /**
     * Administrar miembros exige llegar por la organización y ser su dueño. Un
     * comprador tiene acceso a la unidad, pero no a decidir quién más la tiene; el
     * encargado llega por la organización, pero los datos de compradores no son suyos.
     */
    private UnitAccess requireOrganizationAccess(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);
        if (access.isBuyer()) {
            // 404 y no 403: para el comprador, la administración de la unidad no existe.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada");
        }
        if (!organizationMembership.isOwnerOf(userId, access.unit().organizationId())) {
            throw DevelopmentAccessService.ownerOnly();
        }
        return access;
    }
}