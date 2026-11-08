package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.UnitAccessService;
import com.obraviva.platform.developments.UnitAccessService.UnitAccess;
import com.obraviva.platform.developments.UnitMembershipService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.sharing.ShareLinkDtos.CreateShareLinkRequest;
import com.obraviva.platform.sharing.ShareLinkDtos.CreatedShareLinkResponse;
import com.obraviva.platform.sharing.ShareLinkDtos.ShareLinkSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Emisión, listado y revocación de enlaces públicos.
 *
 * <p>Lo usan tanto el desarrollista como el comprador: compartir la propia unidad es
 * justamente lo que "Mi propiedad" habilita. El acceso se resuelve con
 * {@link UnitAccessService}, que contempla los dos caminos; acá no se asume que quien
 * emite pertenezca a una organización.
 */
@Service
public class ShareLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShareLinkService.class);

    private final ShareLinkRepository shareLinkRepository;
    private final UnitAccessService unitAccess;
    private final UnitMembershipService unitMembership;
    private final OrganizationMembershipService organizationMembership;
    private final UserDirectoryService userDirectory;

    public ShareLinkService(
            ShareLinkRepository shareLinkRepository,
            UnitAccessService unitAccess,
            UnitMembershipService unitMembership,
            OrganizationMembershipService organizationMembership,
            UserDirectoryService userDirectory) {
        this.shareLinkRepository = shareLinkRepository;
        this.unitAccess = unitAccess;
        this.unitMembership = unitMembership;
        this.organizationMembership = organizationMembership;
        this.userDirectory = userDirectory;
    }

    @Transactional
    public CreatedShareLinkResponse create(
            UUID userId, UUID unitId, CreateShareLinkRequest request) {

        UnitAccess access = unitAccess.requireAccess(userId, unitId);

        // Publicar una unidad lo decide su comprador o el dueño del desarrollo. El
        // encargado llega a la unidad por la organización, pero no la publica.
        if (!access.isBuyer()
                && !organizationMembership.isOwnerOf(userId, access.unit().organizationId())) {
            throw DevelopmentAccessService.ownerOnly();
        }

        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El vencimiento del enlace ya pasó");
        }

        // El token vive en memoria el tiempo de esta petición: se guarda su hash y se
        // devuelve una sola vez.
        String token = ShareToken.generate();

        ShareLink link = shareLinkRepository.save(new ShareLink(
                access.unit().organizationId(),
                access.unit().developmentId(),
                unitId,
                ShareToken.hash(token),
                request.label(),
                request.expiresAt(),
                userId));

        log.info("Enlace público {} emitido para la unidad {} por {} (camino {})",
                link.getId(), unitId, userId, access.path());

        return new CreatedShareLinkResponse(
                link.getId(), token, link.getLabel(), link.getExpiresAt(), link.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ShareLinkSummaryResponse> listByUnit(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);

        // Desde la organización, los enlaces que generan los compradores sólo los ve el
        // dueño: para el encargado la lista queda vacía, sin revelar que existen.
        List<ShareLink> links = access.isBuyer()
                ? shareLinkRepository.findByUnitIdInOrderByCreatedAtDesc(List.of(unitId))
                : shareLinkRepository.findByUnitIdAndOrganizationIdInOrderByCreatedAtDesc(
                        unitId, organizationMembership.ownedOrganizationIdsOf(userId));

        // El dueño ve quién creó cada enlace; el comprador, sólo si fue él.
        Map<UUID, UserSummary> authors = access.isBuyer()
                ? Map.of()
                : userDirectory.findAllById(links.stream().map(ShareLink::getCreatedBy).distinct().toList());
        return links.stream().map(link -> toSummary(link, userId, authors.get(link.getCreatedBy()))).toList();
    }

    /**
     * Revocación por cualquiera de los dos caminos.
     *
     * <p>Se prueba primero contra las organizaciones del usuario y después contra sus
     * unidades. Las dos búsquedas están acotadas en la firma del repositorio: no hay
     * un findById suelto con una verificación posterior, que es la forma habitual de
     * que una comprobación se olvide.
     */
    @Transactional
    public ShareLinkSummaryResponse revoke(UUID userId, UUID linkId) {
        ShareLink link = findAccessible(userId, linkId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Enlace no encontrado"));

        link.revoke(userId);
        log.info("Enlace público {} revocado por {}", linkId, userId);
        return toSummary(link, userId, null);
    }

    private Optional<ShareLink> findAccessible(UUID userId, UUID linkId) {
        List<UUID> organizationIds = organizationMembership.ownedOrganizationIdsOf(userId);
        if (!organizationIds.isEmpty()) {
            Optional<ShareLink> byOrganization =
                    shareLinkRepository.findByIdAndOrganizationIdIn(linkId, organizationIds);
            if (byOrganization.isPresent()) {
                return byOrganization;
            }
        }

        List<UUID> unitIds = unitMembership.unitIdsOf(userId);
        if (unitIds.isEmpty()) {
            return Optional.empty();
        }
        return shareLinkRepository.findByIdAndUnitIdIn(linkId, unitIds);
    }

    private static ShareLinkSummaryResponse toSummary(ShareLink link, UUID viewerId, UserSummary author) {
        return new ShareLinkSummaryResponse(
                link.getId(),
                link.getLabel(),
                link.isUsableAt(Instant.now()),
                link.getExpiresAt(),
                link.getRevokedAt(),
                link.getLastAccessedAt(),
                link.getAccessCount(),
                link.getCreatedAt(),
                author == null ? null : (author.fullName() == null || author.fullName().isBlank() ? author.email() : author.fullName()),
                link.getCreatedBy().equals(viewerId));
    }
}