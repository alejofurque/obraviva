package com.obraviva.platform.sharing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    /**
     * Entrada de la vista pública. Es la única búsqueda del sistema que no se acota
     * por organización, y no puede serlo: no hay usuario ni sesión, y el token es la
     * credencial. La acotación la da el propio hash, que es irrepetible.
     */
    Optional<ShareLink> findByTokenHash(String tokenHash);

    /** Panel del emisor: los enlaces de una unidad, el más nuevo primero. */
    List<ShareLink> findByUnitIdAndOrganizationIdInOrderByCreatedAtDesc(
            UUID unitId, Collection<UUID> organizationIds);

    /**
     * Los mismos enlaces, acotados por unidad en vez de por organización: es el camino
     * del comprador, que no pertenece a ninguna organización. La acotación sigue
     * viviendo en la firma.
     */
    List<ShareLink> findByUnitIdInOrderByCreatedAtDesc(Collection<UUID> unitIds);

    Optional<ShareLink> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);

    /** Revocación por parte del comprador: acotada a sus unidades. */
    Optional<ShareLink> findByIdAndUnitIdIn(UUID id, Collection<UUID> unitIds);
}