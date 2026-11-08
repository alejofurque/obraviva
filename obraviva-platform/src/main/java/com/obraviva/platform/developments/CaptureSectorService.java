package com.obraviva.platform.developments;

import com.obraviva.platform.captures3d.CaptureCatalogService;
import com.obraviva.platform.media.MediaCatalogService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sectores del recorrido 3D de una casa: los lugares que se graban en cada fecha para que
 * el comprador viaje en el tiempo sector por sector. Los define el dueño; el encargado los
 * ve como la lista de lo que tiene que grabar.
 *
 * <p>Cada sector es un video por fecha, y con la reconstrucción real cada video consume un
 * crédito: la cantidad de sectores multiplica el costo de cada recorrido. Por eso hay un
 * tope y la propuesta estándar es corta.
 */
@Service
public class CaptureSectorService {

    /** Propuesta para una casa: el frente y los ambientes que más cambian a la vista. */
    public static final List<String> STANDARD_SECTORS = List.of("Frente", "Living y cocina", "Dormitorio principal", "Baño");

    static final int MAX_SECTORS = 12;

    private final CaptureSectorRepository sectorRepository;
    private final UnitRepository unitRepository;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final CaptureCatalogService captureCatalog;
    private final MediaCatalogService mediaCatalog;

    public CaptureSectorService(
            CaptureSectorRepository sectorRepository,
            UnitRepository unitRepository,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            CaptureCatalogService captureCatalog,
            MediaCatalogService mediaCatalog) {
        this.captureCatalog = captureCatalog;
        this.mediaCatalog = mediaCatalog;
        this.sectorRepository = sectorRepository;
        this.unitRepository = unitRepository;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
    }

    /**
     * La lista nueva de sectores, en orden. Cada fila dice qué sector era antes
     * ({@code previousName}): con eso, cambiar el texto de una fila es un renombre, que
     * conserva el sector y lleva sus fotos y recorridos al nombre nuevo, y no quitar uno y
     * agregar otro. Sin {@code previousName}, es un sector nuevo.
     *
     * <p>{@code names} es la forma anterior, una lista de nombres: lo que coincide con un
     * sector existente se conserva y lo demás es nuevo. Sirve para la plantilla y los
     * clientes viejos; con ella un cambio de texto es quitar y agregar.
     */
    public record ReplaceSectorsRequest(
            @Size(max = MAX_SECTORS) List<@Size(max = 80) String> names,
            @Size(max = MAX_SECTORS) List<@Valid @NotNull SectorChange> sectors) {

        public ReplaceSectorsRequest(List<String> names) {
            this(names, null);
        }
    }

    /**
     * Una fila del editor. {@code front} marca el frente de la casa (a lo sumo uno); nulo
     * conserva lo que el sector ya tenía, y un sector nuevo arranca sin marca.
     */
    public record SectorChange(@NotNull @Size(max = 80) String name, @Size(max = 80) String previousName, Boolean front) {

        public SectorChange(String name, String previousName) {
            this(name, previousName, null);
        }
    }

    /** Un sector como lo ve el editor: su nombre y si es el frente. */
    public record SectorView(String name, boolean front) {
    }

    public record RenameSectorRequest(@NotNull @Size(max = 80) String from, @NotNull @Size(max = 80) String to) {
    }

    @Transactional(readOnly = true)
    public List<String> list(UUID userId, UUID unitId) {
        requireWorkableUnit(userId, unitId);
        return names(unitId);
    }

    /** Los sectores con la marca del frente, para el editor del dueño y la sesión del encargado. */
    @Transactional(readOnly = true)
    public List<SectorView> detail(UUID userId, UUID unitId) {
        requireWorkableUnit(userId, unitId);
        return sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId).stream()
                .map(sector -> new SectorView(sector.getName(), sector.isFront()))
                .toList();
    }

    /**
     * El sector marcado como frente, si hay. La portada viva, "Cuando empezó" y la
     * comparación del proyecto se apoyan en él, no en el nombre: renombrarlo no rompe nada.
     * Para quien ya verificó el acceso.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> frontOf(UUID unitId) {
        return sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId).stream()
                .filter(CaptureSector::isFront)
                .map(CaptureSector::getName)
                .findFirst();
    }

    /** Nombres en orden, para quien ya verificó el acceso: la vista del comprador. */
    @Transactional(readOnly = true)
    public List<String> names(UUID unitId) {
        return sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId).stream().map(CaptureSector::getName).toList();
    }

    /**
     * El sector de una foto o video, validado contra su casa: uno de los suyos, o nulo
     * ("General"). Una foto de obras comunes no lleva sector, porque los sectores son de una
     * casa. Para quien ya verificó el acceso a la casa.
     */
    @Transactional(readOnly = true)
    public String requireSectorOf(UUID unitId, String sector) {
        if (sector == null || sector.isBlank()) {
            return null;
        }
        String name = sector.trim();
        if (unitId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Las fotos de obras comunes no llevan sector: los sectores son de una casa");
        }
        if (!names(unitId).contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"%s\" no es un sector de esta casa. Los sectores se definen en la pestaña Recorridos 3D de la casa."
                            .formatted(name));
        }
        return name;
    }

    /**
     * Reemplaza la lista de sectores calculando las diferencias con la actual: conserva los
     * que siguen (con su id), renombra en cascada, agrega los nuevos, reordena y quita los que
     * ya no están. Quitar un sector que tiene fotos o recorridos publicados se rechaza con
     * 409: el material quedaría colgado de un lugar que ya no existe.
     */
    @Transactional
    public List<String> replace(UUID userId, UUID unitId, ReplaceSectorsRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);
        List<CaptureSector> existing = sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId);
        List<SectorChange> changes = changesOf(request, existing);
        apply(unit, existing, changes);
        return names(unitId);
    }

    /**
     * Renombra un sector y, en la misma transacción, sus recorridos y sus fotos de esa casa.
     * Si el nombre nuevo ya es otro sector de la casa, 409.
     */
    @Transactional
    public List<String> rename(UUID userId, UUID unitId, RenameSectorRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);
        List<CaptureSector> existing = sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId);
        String from = request.from().trim();
        String to = normalize(request.to());
        if (to.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El sector tiene que tener nombre");
        }
        if (existing.stream().noneMatch(sector -> sector.getName().equals(from))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"%s\" no es un sector de esta casa".formatted(from));
        }
        if (existing.stream().anyMatch(sector -> !sector.getName().equals(from) && sector.getName().equalsIgnoreCase(to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La casa ya tiene un sector \"%s\"".formatted(to));
        }
        List<SectorChange> changes = existing.stream()
                .map(sector -> new SectorChange(sector.getName().equals(from) ? to : sector.getName(), sector.getName(), null))
                .toList();
        apply(unit, existing, changes);
        return names(unitId);
    }

    /** Para quien ya verificó al dueño: el alta de casas con plantilla. */
    void applyStandard(Unit unit) {
        if (sectorRepository.findByUnitIdOrderByOrderIndexAsc(unit.getId()).isEmpty()) {
            for (int i = 0; i < STANDARD_SECTORS.size(); i++) {
                CaptureSector sector = new CaptureSector(unit.getOrganizationId(), unit.getId(), STANDARD_SECTORS.get(i), i + 1);
                // La plantilla trae el frente marcado: es el primero, "Frente".
                sector.markFront(i == 0);
                sectorRepository.save(sector);
            }
        }
    }

    /** Lo que pide el cliente, como cambios fila por fila. La forma con nombres conserva lo que coincide. */
    private static List<SectorChange> changesOf(ReplaceSectorsRequest request, List<CaptureSector> existing) {
        if (request.sectors() != null) {
            return request.sectors();
        }
        if (request.names() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la lista de sectores");
        }
        Set<String> current = new HashSet<>();
        existing.forEach(sector -> current.add(sector.getName()));
        return request.names().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new SectorChange(name, current.contains(normalize(name)) ? normalize(name) : null, null))
                .toList();
    }

    /**
     * Valida todo y recién después escribe: quita, renombra (en dos tiempos, para que un
     * intercambio de nombres no choque con la restricción de nombres únicos ni mezcle el
     * material de dos sectores), agrega y reordena.
     */
    private void apply(Unit unit, List<CaptureSector> existing, List<SectorChange> requested) {
        Map<String, CaptureSector> byName = new LinkedHashMap<>();
        existing.forEach(sector -> byName.put(sector.getName(), sector));

        List<SectorChange> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> kept = new HashSet<>();
        for (SectorChange change : requested) {
            String name = normalize(change.name());
            if (name.isEmpty()) {
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El sector \"" + name + "\" está repetido");
            }
            String previous = change.previousName() == null ? null : change.previousName().trim();
            if (previous != null) {
                if (!byName.containsKey(previous)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "\"%s\" no es un sector de esta casa".formatted(previous));
                }
                if (!kept.add(previous)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "El sector \"%s\" aparece dos veces en la lista".formatted(previous));
                }
            }
            changes.add(new SectorChange(name, previous, change.front()));
        }
        if (changes.size() > MAX_SECTORS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Una casa tiene hasta " + MAX_SECTORS + " sectores");
        }
        // El frente que queda: el marcado en la fila o, sin decirlo, el que el sector ya tenía.
        List<Boolean> fronts = changes.stream()
                .map(change -> change.front() != null
                        ? change.front()
                        : change.previousName() != null && byName.get(change.previousName()).isFront())
                .toList();
        if (fronts.stream().filter(Boolean::booleanValue).count() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Una casa tiene un solo frente: marcá uno");
        }

        UUID organizationId = unit.getOrganizationId();
        List<CaptureSector> removed = existing.stream().filter(sector -> !kept.contains(sector.getName())).toList();
        for (CaptureSector sector : removed) {
            long photos = mediaCatalog.countInSector(organizationId, unit.getId(), sector.getName());
            long tours = captureCatalog.countInZone(organizationId, unit.getId(), sector.getName());
            if (photos + tours > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "%s tiene %s y %s: renombralo o movelos antes de quitarlo."
                        .formatted(sector.getName(), count(photos, "foto", "fotos"), count(tours, "recorrido", "recorridos")));
            }
        }

        sectorRepository.deleteAll(removed);
        sectorRepository.flush();

        List<SectorChange> renames = changes.stream()
                .filter(change -> change.previousName() != null && !change.previousName().equals(change.name()))
                .toList();
        Map<SectorChange, String> temporary = new LinkedHashMap<>();
        for (SectorChange change : renames) {
            String temp = "~" + UUID.randomUUID();
            temporary.put(change, temp);
            byName.get(change.previousName()).rename(temp);
            captureCatalog.renameZone(organizationId, unit.getId(), change.previousName(), temp);
            mediaCatalog.renameSector(organizationId, unit.getId(), change.previousName(), temp);
        }
        sectorRepository.flush();
        for (SectorChange change : renames) {
            String temp = temporary.get(change);
            byName.get(change.previousName()).rename(change.name());
            captureCatalog.renameZone(organizationId, unit.getId(), temp, change.name());
            mediaCatalog.renameSector(organizationId, unit.getId(), temp, change.name());
        }

        List<CaptureSector> ordered = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            SectorChange change = changes.get(i);
            if (change.previousName() == null) {
                ordered.add(sectorRepository.save(new CaptureSector(organizationId, unit.getId(), change.name(), i + 1)));
            } else {
                CaptureSector sector = byName.get(change.previousName());
                sector.moveTo(i + 1);
                ordered.add(sector);
            }
        }
        // Primero se desmarcan todos y después se marca el frente: pasar la marca de un
        // sector a otro no puede chocar con el índice único parcial.
        ordered.forEach(sector -> sector.markFront(false));
        sectorRepository.flush();
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).markFront(fronts.get(i));
        }
        sectorRepository.flush();
    }

    private static String count(long value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }

    /** Sin espacios de más. */
    private static String normalize(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    private Unit requireOwnedUnit(UUID userId, UUID unitId) {
        Unit unit = requireWorkableUnit(userId, unitId);
        if (!membershipService.isOwnerOf(userId, unit.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return unit;
    }

    private Unit requireWorkableUnit(UUID userId, UUID unitId) {
        return unitRepository
                .findByIdAndOrganizationIdIn(unitId, membershipService.organizationIdsOf(userId))
                .filter(unit -> developmentAccess.canAccess(userId, unit.getDevelopment().getId(), unit.getOrganizationId()))
                .filter(unit -> developmentAccess.canWorkOnUnit(
                        userId, unit.getDevelopment().getId(), unit.getOrganizationId(), unit.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));
    }

    @RestController
    @RequestMapping("/api/units/{unitId}/sectors")
    public static class CaptureSectorController {

        private final CaptureSectorService sectorService;

        public CaptureSectorController(CaptureSectorService sectorService) {
            this.sectorService = sectorService;
        }

        @GetMapping
        public List<String> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
            return sectorService.list(UUID.fromString(jwt.getSubject()), unitId);
        }

        @PutMapping
        public List<String> replace(
                @AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId,
                @Valid @RequestBody ReplaceSectorsRequest request) {
            return sectorService.replace(UUID.fromString(jwt.getSubject()), unitId, request);
        }

        @GetMapping("/detail")
        public List<SectorView> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
            return sectorService.detail(UUID.fromString(jwt.getSubject()), unitId);
        }

        @PatchMapping("/rename")
        public List<String> rename(
                @AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId,
                @Valid @RequestBody RenameSectorRequest request) {
            return sectorService.rename(UUID.fromString(jwt.getSubject()), unitId, request);
        }
    }
}
