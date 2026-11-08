package com.obraviva.platform.organizations;

import com.obraviva.platform.auth.AccountProvisioningService;
import com.obraviva.platform.auth.AccountProvisioningService.ProvisionedAccount;
import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.organizations.OrganizationMemberDtos.AddedOrganizationMemberResponse;
import com.obraviva.platform.organizations.OrganizationMemberDtos.OrganizationMemberResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Equipo de una organización: el dueño suma, lista y quita encargados.
 *
 * <p>Toda operación exige ser dueño. Quien no pertenece a la organización recibe 404;
 * el encargado, que sí pertenece, recibe 403.
 */
@Service
public class OrganizationMemberService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationMemberService.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationMembershipService membershipService;
    private final AccountProvisioningService accountProvisioning;
    private final UserDirectoryService userDirectory;

    public OrganizationMemberService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            OrganizationMembershipService membershipService,
            AccountProvisioningService accountProvisioning,
            UserDirectoryService userDirectory) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.membershipService = membershipService;
        this.accountProvisioning = accountProvisioning;
        this.userDirectory = userDirectory;
    }

    @Transactional
    public AddedOrganizationMemberResponse add(
            UUID userId, UUID organizationId, AddOrganizationMemberRequest request) {
        requireOwner(userId, organizationId);

        ProvisionedAccount account = accountProvisioning
                .findOrCreate(request.email(), request.fullName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No hay ninguna cuenta con ese correo. Indicá el nombre para crearla."));
        UserSummary user = account.user();

        if (memberRepository.existsByOrganizationIdAndUserId(organizationId, user.id())) {
            throw alreadyMember();
        }

        OrganizationMember member;
        try {
            member = memberRepository.saveAndFlush(new OrganizationMember(
                    organizationRepository.getReferenceById(organizationId),
                    user.id(),
                    OrganizationRole.MEMBER));
        } catch (DataIntegrityViolationException e) {
            // Misma ventana que en el alta de unidades: la verificación previa no es atómica.
            throw alreadyMember();
        }

        log.info("Encargado {} sumado a la organización {} por {} (cuenta nueva: {})",
                user.id(), organizationId, userId, account.created());

        return new AddedOrganizationMemberResponse(
                member.getId(),
                user.id(),
                user.email(),
                user.fullName(),
                member.getRole(),
                member.getCreatedAt(),
                account.created(),
                account.initialPassword());
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> list(UUID userId, UUID organizationId) {
        requireOwner(userId, organizationId);

        List<OrganizationMember> members =
                memberRepository.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
        Map<UUID, UserSummary> users = userDirectory.findAllById(
                members.stream().map(OrganizationMember::getUserId).toList());

        return members.stream()
                .map(member -> {
                    UserSummary user = users.get(member.getUserId());
                    return new OrganizationMemberResponse(
                            member.getId(),
                            member.getUserId(),
                            user == null ? null : user.email(),
                            user == null ? null : user.fullName(),
                            member.getRole(),
                            member.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public void remove(UUID userId, UUID organizationId, UUID memberId) {
        requireOwner(userId, organizationId);

        OrganizationMember member = memberRepository
                .findByIdAndOrganizationId(memberId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Miembro no encontrado"));

        if (member.getRole() == OrganizationRole.OWNER) {
            // Una organización sin dueño quedaría sin nadie que la administre.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El dueño de la organización no se puede quitar");
        }

        memberRepository.delete(member);
        log.info("Miembro {} quitado de la organización {} por {}", memberId, organizationId, userId);
    }

    private void requireOwner(UUID userId, UUID organizationId) {
        OrganizationRole role = membershipService.rolesOf(userId).get(organizationId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada");
        }
        if (role != OrganizationRole.OWNER) {
            throw OrganizationMembershipService.ownerOnly();
        }
    }

    private static ResponseStatusException alreadyMember() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Esa persona ya forma parte de la organización");
    }
}
