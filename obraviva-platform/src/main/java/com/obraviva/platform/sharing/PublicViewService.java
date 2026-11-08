package com.obraviva.platform.sharing;

import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.developments.UnitCatalogService.UnitSummary;
import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Resuelve un token de enlace y arma lo que se muestra sin sesión.
 *
 * <p>Todas las consultas se acotan por la organización del propio enlace: no hay
 * ninguna búsqueda sin acotar acá, aunque no exista usuario. La seguridad sigue
 * viviendo en la firma de los métodos de repositorio.
 */
@Service
public class PublicViewService {

    private static final Logger log = LoggerFactory.getLogger(PublicViewService.class);

    /**
     * Mismo mensaje para todo token que no sirve. Distinguir "no existe" de "vencido"
     * o "revocado" le confirmaría a quien prueba tokens que uno de ellos existió, y
     * cuándo dejó de servir.
     */
    private static final String NOT_FOUND_MESSAGE = "El enlace no existe o ya no está disponible";

    private final ShareLinkRepository shareLinkRepository;
    private final UnitCatalogService unitCatalog;
    private final UnitViewAssembler assembler;

    public PublicViewService(
            ShareLinkRepository shareLinkRepository,
            UnitCatalogService unitCatalog,
            UnitViewAssembler assembler) {
        this.shareLinkRepository = shareLinkRepository;
        this.unitCatalog = unitCatalog;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public PublicUnitView view(String token) {
        ShareLink link = resolve(token);

        UnitSummary unit = unitCatalog
                .findInOrganization(link.getUnitId(), link.getOrganizationId())
                .orElseThrow(PublicViewService::notFound);

        // Vigencia corta: la URL firmada sobrevive a la revocación del enlace porque
        // el almacenamiento no consulta nada al servirla.
        return assembler.assemble(unit, assembler.publicAssetTtl());
    }

    /**
     * Registra la visita en su propia transacción.
     *
     * <p>Va separado del armado de la vista y con propagación nueva a propósito: si
     * falla el contador, la familia igual tiene que poder ver la propiedad. Contar
     * visitas es información para el emisor, no una condición del acceso.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerAccess(String token) {
        try {
            shareLinkRepository.findByTokenHash(ShareToken.hash(token))
                    .ifPresent(ShareLink::registerAccess);
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar el acceso a un enlace público", e);
        }
    }

    private ShareLink resolve(String token) {
        if (token == null || token.isBlank()) {
            throw notFound();
        }
        ShareLink link = shareLinkRepository
                .findByTokenHash(ShareToken.hash(token))
                .orElseThrow(PublicViewService::notFound);

        if (!link.isUsableAt(Instant.now())) {
            throw notFound();
        }
        return link;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
}