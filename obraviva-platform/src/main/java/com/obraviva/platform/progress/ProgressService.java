package com.obraviva.platform.progress;

import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.UnitCatalogService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class ProgressService {

    private final ProgressEntryRepository progressRepository;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;
    private final StageCatalogService stageCatalog;
    private final UnitCatalogService unitCatalog;
    private final UserDirectoryService userDirectory;

    public ProgressService(
            ProgressEntryRepository progressRepository,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService,
            StageCatalogService stageCatalog,
            UnitCatalogService unitCatalog,
            UserDirectoryService userDirectory) {
        this.progressRepository = progressRepository;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
        this.stageCatalog = stageCatalog;
        this.unitCatalog = unitCatalog;
        this.userDirectory = userDirectory;
    }

    /**
     * Avance vigente visto desde fuera del módulo. {@code rootId} identifica el hilo de
     * versiones: lo que se vinculó a una versión anterior, como una foto, se reconoce
     * por él. {@code unitId} nulo: novedad de las obras comunes.
     */
    public record ProgressSummary(
            UUID id,
            UUID stageId,
            LocalDate entryDate,
            String description,
            String source,
            UUID rootId,
            int version,
            UUID unitId) {

        public boolean corrected() {
            return version > 1;
        }
    }

    /**
     * Raíz de cada versión de los avances de un desarrollo, incluidas las superadas.
     *
     * <p>Una foto queda vinculada a la versión que existía cuando se subió. Si después el
     * avance se corrige, la versión vigente es otra; con este mapa la foto se sigue
     * encontrando en su avance. No verifica acceso: recibe las organizaciones ya
     * resueltas por quien llama.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> rootIdsByEntryId(UUID developmentId, List<UUID> organizationIds) {
        return progressRepository
                .findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds)
                .stream()
                .collect(Collectors.toMap(ProgressEntry::getId, ProgressEntry::getRootId));
    }

    /** Lo que un avance le hizo a su etapa al publicarse: porcentaje y estado, antes y después. */
    public record StageChange(Integer percentBefore, Integer percentAfter, String statusBefore, String statusAfter) {
    }

    /**
     * El cambio de etapa de cada avance que lo trajo, por la raíz de su hilo: se guarda en la
     * primera versión, la que se publicó, y una corrección no lo repite (V32). No verifica
     * acceso: recibe las organizaciones resueltas por quien llama.
     */
    @Transactional(readOnly = true)
    public Map<UUID, StageChange> stageChangesByRoot(UUID developmentId, List<UUID> organizationIds) {
        return progressRepository
                .findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds)
                .stream()
                .filter(entry -> stageChangeOf(entry) != null)
                .collect(Collectors.toMap(ProgressEntry::getRootId, ProgressService::stageChangeOf));
    }

    /** El cambio de etapa que trajo esta versión; nulo si no tocó la etapa o si es una corrección. */
    static StageChange stageChangeOf(ProgressEntry entry) {
        if (entry.getVersion() != 1 || (entry.getStagePercentAfter() == null && entry.getStageStatusAfter() == null)) {
            return null;
        }
        return new StageChange(
                entry.getStagePercentBefore(),
                entry.getStagePercentAfter(),
                entry.getStageStatusBefore() == null ? null : entry.getStageStatusBefore().name(),
                entry.getStageStatusAfter() == null ? null : entry.getStageStatusAfter().name());
    }

    /**
     * Los avances vigentes, cada uno con quién lo publicó y qué le hizo a su etapa, los dos de
     * la primera versión del hilo: una corrección cambia el texto, no el autor ni la etapa.
     */
    @Transactional(readOnly = true)
    public List<ProgressEntryResponse> timeline(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        Predicate<UUID> readable = developmentAccess.readableUnits(userId, developmentId, organizationId);
        List<UUID> organizationIds = membershipService.organizationIdsOf(userId);
        Map<UUID, ProgressEntry> roots = progressRepository
                .findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds)
                .stream()
                .filter(entry -> entry.getVersion() == 1)
                .collect(Collectors.toMap(ProgressEntry::getRootId, entry -> entry, (first, second) -> first));
        Map<UUID, UserSummary> authors = userDirectory.findAllById(
                roots.values().stream().map(ProgressEntry::getCreatedBy).distinct().toList());
        return progressRepository
                .findByDevelopmentIdAndOrganizationIdInAndSupersededAtIsNullOrderByEntryDateDesc(
                        developmentId, organizationIds)
                .stream()
                .filter(entry -> readable.test(entry.getUnitId()))
                .map(entry -> {
                    ProgressEntry root = roots.getOrDefault(entry.getRootId(), entry);
                    UserSummary author = authors.get(root.getCreatedBy());
                    return ProgressEntryResponse.from(entry, nameOf(author), stageChangeOf(root));
                })
                .toList();
    }

    private static String nameOf(UserSummary user) {
        if (user == null) {
            return null;
        }
        return user.fullName() == null || user.fullName().isBlank() ? user.email() : user.fullName();
    }

    /**
     * Línea de tiempo completa para quien ya tiene acceso resuelto por otro medio.
     *
     * <p>No verifica acceso ni recibe usuario: la usa la vista pública, que se
     * autentica con un token de enlace y aporta la organización de ese enlace. La
     * búsqueda sigue acotada por organización en la firma del repositorio.
     */
    @Transactional(readOnly = true)
    public List<ProgressSummary> currentTimeline(UUID developmentId, List<UUID> organizationIds) {
        return progressRepository
                .findByDevelopmentIdAndOrganizationIdInAndSupersededAtIsNullOrderByEntryDateDesc(
                        developmentId, organizationIds)
                .stream()
                .map(ProgressService::toSummary)
                .toList();
    }

    /**
     * Avances vigentes del período, para el motor de reportes.
     *
     * <p>No verifica acceso: quien llama ya lo hizo y este método recibe las
     * organizaciones resueltas, mismo criterio que {@link #publishFromAudio}.
     */
    @Transactional(readOnly = true)
    public List<ProgressSummary> currentEntriesInPeriod(
            UUID developmentId, List<UUID> organizationIds, LocalDate from, LocalDate to) {
        return progressRepository
                .findByDevelopmentIdAndOrganizationIdInAndSupersededAtIsNullAndEntryDateBetweenOrderByEntryDateAsc(
                        developmentId, organizationIds, from, to)
                .stream()
                .map(ProgressService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProgressEntryResponse> history(UUID userId, UUID rootId) {
        List<ProgressEntry> versions = progressRepository
                .findByRootIdAndOrganizationIdInOrderByVersionAsc(
                        rootId, membershipService.organizationIdsOf(userId));
        // Un avance de una casa que el encargado no tiene es, para él, un avance que no existe.
        if (versions.isEmpty()
                || !developmentAccess.canAccess(userId, versions.getFirst().getDevelopmentId(), versions.getFirst().getOrganizationId())
                || !developmentAccess.readableUnits(userId, versions.getFirst().getDevelopmentId(), versions.getFirst().getOrganizationId())
                        .test(versions.getFirst().getUnitId())) {
            throw notFound();
        }
        return versions.stream().map(ProgressEntryResponse::from).toList();
    }

    @Transactional
    public ProgressEntryResponse create(
            UUID userId, UUID developmentId, CreateProgressEntryRequest request) {
        return create(userId, developmentId, request, ProgressSource.MANUAL);
    }

    /**
     * Alta con su origen: lo que sale de un audio aprobado pasa por las mismas
     * validaciones que lo cargado a mano y queda marcado como de audio, distinción que
     * la línea de tiempo muestra al comprador.
     */
    @Transactional
    public ProgressEntryResponse create(
            UUID userId, UUID developmentId, CreateProgressEntryRequest request, ProgressSource source) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        requireUnitOf(developmentId, organizationId, request.unitId());
        developmentAccess.requireWorkOnUnit(userId, developmentId, organizationId, request.unitId());
        requireStageOf(developmentId, organizationId, request.stageId(), request.unitId());
        requireNotFuture(request.entryDate());
        ProgressEntry entry = new ProgressEntry(
                organizationId,
                developmentId,
                request.stageId(),
                request.entryDate(),
                request.description(),
                source,
                userId);
        entry.assignUnit(request.unitId());
        return ProgressEntryResponse.from(progressRepository.save(entry));
    }

    /** Guarda en el avance el cambio que le hizo a su etapa, para contarlo después ("35% → 50%"). */
    @Transactional
    public void recordStageChange(UUID entryId, UUID organizationId, StageCatalogService.StageChange change) {
        progressRepository.findByIdAndOrganizationIdIn(entryId, List.of(organizationId))
                .ifPresent(entry -> entry.recordStageChange(
                        change.percentBefore(), change.percentAfter(), change.statusBefore(), change.statusAfter()));
    }

    /**
     * Corrección: no sobrescribe. Marca la versión actual como superada
     * y persiste una versión nueva con el contenido corregido.
     */
    @Transactional
    public ProgressEntryResponse createCorrection(
            UUID userId, UUID entryId, UpdateProgressEntryRequest request) {
        ProgressEntry current = progressRepository
                .findByIdAndOrganizationIdIn(entryId, membershipService.organizationIdsOf(userId))
                .orElseThrow(ProgressService::notFound);
        if (!developmentAccess.canAccess(userId, current.getDevelopmentId(), current.getOrganizationId())) {
            throw notFound();
        }

        if (!current.isCurrent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Esa versión ya fue superada; corregí la versión vigente");
        }
        developmentAccess.requireWorkOnUnit(userId, current.getDevelopmentId(), current.getOrganizationId(), current.getUnitId());

        requireStageOf(current.getDevelopmentId(), current.getOrganizationId(), request.stageId(), current.getUnitId());
        requireNotFuture(request.entryDate());
        ProgressEntry next = current.createNextVersion(
                request.stageId(), request.entryDate(), request.description(), userId);
        return ProgressEntryResponse.from(progressRepository.save(next));
    }

    /**
     * Avance de origen audio para la carga de demostración, sin validar acceso: recibe
     * la organización resuelta.
     *
     * <p>Un audio aprobado ya no pasa por acá sino por la publicación atómica
     * ({@code publication.ProgressPublicationService}), que valida igual que el alta
     * manual y suma fotos y cambio de etapa. Esto queda sólo para los datos de ejemplo.
     */
    @Transactional
    public ProgressEntryResponse publishFromAudio(
            UUID organizationId,
            UUID developmentId,
            UUID stageId,
            LocalDate entryDate,
            String description,
            UUID publishedBy,
            UUID unitId) {
        ProgressEntry entry = new ProgressEntry(
                organizationId,
                developmentId,
                stageId,
                entryDate,
                description,
                ProgressSource.AUDIO,
                publishedBy);
        entry.assignUnit(unitId);
        return ProgressEntryResponse.from(progressRepository.save(entry));
    }

    private static ProgressSummary toSummary(ProgressEntry entry) {
        return new ProgressSummary(
                entry.getId(),
                entry.getStageId(),
                entry.getEntryDate(),
                entry.getDescription(),
                entry.getSource().name(),
                entry.getRootId(),
                entry.getVersion(),
                entry.getUnitId());
    }

    /**
     * Raíz del hilo de versiones de un avance de la obra, o vacío si el avance no existe o
     * es de otra obra. Lo usa la subida de fotos para validar a qué avance se cuelgan.
     * No verifica acceso: recibe la organización ya resuelta por quien llama.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> rootOf(UUID developmentId, UUID organizationId, UUID entryId) {
        return progressRepository.findByIdAndOrganizationIdIn(entryId, List.of(organizationId))
                .filter(entry -> entry.getDevelopmentId().equals(developmentId))
                .map(ProgressEntry::getRootId);
    }

    /**
     * La etapa que manda el cliente tiene que ser de la obra. Sin esto, un avance podría
     * quedar asociado a la etapa de otro desarrollo y aparecer con ese nombre.
     */
    /** La etapa tiene que ser de la misma casa del avance, o común si el avance es común. */
    private void requireStageOf(UUID developmentId, UUID organizationId, UUID stageId, UUID unitId) {
        if (stageId == null) {
            return;
        }
        boolean belongs = stageCatalog.stagesForScope(developmentId, unitId, List.of(organizationId)).stream()
                .anyMatch(stage -> stage.id().equals(stageId));
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    unitId == null ? "La etapa no pertenece a las obras comunes" : "La etapa no pertenece a esta casa");
        }
    }

    /** Un avance cuenta lo que pasó: no puede tener fecha futura, lo cargue quien lo cargue. */
    private static void requireNotFuture(LocalDate entryDate) {
        if (entryDate.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha del avance no puede ser futura");
        }
    }

    private void requireUnitOf(UUID developmentId, UUID organizationId, UUID unitId) {
        if (unitId == null) {
            return;
        }
        boolean belongs = unitCatalog.findInOrganization(unitId, organizationId)
                .filter(unit -> unit.developmentId().equals(developmentId))
                .isPresent();
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }
    }

    /**
     * Las versiones de un avance (su hilo), por id: las fotos quedan en la versión a la que se
     * subieron, así que contar las de un avance es contar las de todas sus versiones.
     */
    @Transactional(readOnly = true)
    public List<UUID> versionIdsOf(UUID organizationId, UUID rootId) {
        return progressRepository.findByRootIdAndOrganizationIdInOrderByVersionAsc(rootId, List.of(organizationId)).stream()
                .map(ProgressEntry::getId).toList();
    }

    /** Casa del avance: la usa la subida de fotos para ubicarlas. Vacío si el avance no existe o es común. */
    @Transactional(readOnly = true)
    public Optional<UUID> unitOf(UUID developmentId, UUID organizationId, UUID entryId) {
        return progressRepository.findByIdAndOrganizationIdIn(entryId, List.of(organizationId))
                .filter(entry -> entry.getDevelopmentId().equals(developmentId))
                .map(ProgressEntry::getUnitId);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Avance no encontrado");
    }
}