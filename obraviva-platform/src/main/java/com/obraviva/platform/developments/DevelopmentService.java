package com.obraviva.platform.developments;

import com.obraviva.platform.developments.StageCatalogService.StageProgress;
import com.obraviva.platform.developments.UnitDetailsDtos.CreateUnitsBatchRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitRequest;
import com.obraviva.platform.media.variants.ImageVariants;
import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Desarrollos de casas y sus casas.
 *
 * <p>Cada casa es una obra independiente con sus etapas; el desarrollo agrega el avance
 * de sus casas y tiene, además, sus obras comunes (etapas sin casa).
 */
@Service
public class DevelopmentService {

    /** Código de la unidad de una obra única. No se muestra: la obra es la unidad. */
    static final String SINGLE_UNIT_CODE = "Principal";

    private final DevelopmentRepository developmentRepository;
    private final UnitRepository unitRepository;
    private final StageRepository stageRepository;
    private final UnitMemberRepository unitMemberRepository;
    private final OrganizationMembershipService membershipService;
    private final DevelopmentAccessService developmentAccess;
    private final UnitStageService unitStages;
    private final CaptureSectorService captureSectors;
    private final StorageService storageService;
    private final ImageVariants imageVariants;

    public DevelopmentService(
            DevelopmentRepository developmentRepository,
            UnitRepository unitRepository,
            StageRepository stageRepository,
            UnitMemberRepository unitMemberRepository,
            OrganizationMembershipService membershipService,
            DevelopmentAccessService developmentAccess,
            UnitStageService unitStages,
            CaptureSectorService captureSectors,
            StorageService storageService,
            ImageVariants imageVariants) {
        this.imageVariants = imageVariants;
        this.developmentRepository = developmentRepository;
        this.unitRepository = unitRepository;
        this.stageRepository = stageRepository;
        this.unitMemberRepository = unitMemberRepository;
        this.membershipService = membershipService;
        this.developmentAccess = developmentAccess;
        this.unitStages = unitStages;
        this.captureSectors = captureSectors;
        this.storageService = storageService;
    }

    // --- Desarrollos ---

    @Transactional(readOnly = true)
    public List<DevelopmentResponse> listForUser(UUID userId) {
        Map<UUID, OrganizationRole> roles = membershipService.rolesOf(userId);
        // El dueño ve todas las obras de su organización; el encargado, las asignadas.
        Set<UUID> visible = developmentAccess.visibleDevelopmentIds(userId);
        List<Development> developments = developmentRepository.findByOrganizationIdIn(roles.keySet()).stream()
                .filter(development -> visible.contains(development.getId()))
                .toList();
        List<UUID> ids = developments.stream().map(Development::getId).toList();
        Map<UUID, List<StageProgress>> stages = stageProgressByDevelopment(ids, roles.keySet());
        Map<UUID, Long> units = unitCountByDevelopment(ids, roles.keySet());
        // Las portadas con sus versiones livianas, en una sola consulta.
        ImageVariants.Images covers = imageVariants.lookup(roles.keySet(),
                developments.stream().map(Development::getCoverStorageKey).toList());
        return developments.stream()
                .map(development -> scoped(userId, development, DevelopmentResponse.from(
                        development,
                        roles.get(development.getOrganizationId()),
                        stages.getOrDefault(development.getId(), List.of()),
                        units.getOrDefault(development.getId(), 0L).intValue(),
                        covers.sign(development.getCoverStorageKey())), stages.getOrDefault(development.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DevelopmentResponse detail(UUID userId, UUID developmentId) {
        Map<UUID, OrganizationRole> roles = membershipService.rolesOf(userId);
        Development development = developmentAccess.requireVisible(userId, developmentId);
        return scoped(userId, development,
                toResponse(development, roles.get(development.getOrganizationId()), roles.keySet()),
                stageProgressByDevelopment(List.of(developmentId), roles.keySet()).getOrDefault(developmentId, List.of()));
    }

    /** Un encargado de casas puntuales recibe el desarrollo con sus casas y nada más. */
    private DevelopmentResponse scoped(
            UUID userId, Development development, DevelopmentResponse response, List<StageProgress> stages) {
        return developmentAccess.houseScope(userId, development.getId(), development.getOrganizationId())
                .map(houses -> response.restrictedTo(unitRepository
                        .findByDevelopmentIdAndOrganizationIdIn(development.getId(), List.of(development.getOrganizationId()))
                        .stream().map(Unit::getId).filter(houses::contains).toList(), stages))
                .orElse(response);
    }

    DevelopmentResponse toResponse(Development development, OrganizationRole role, Collection<UUID> organizationIds) {
        UUID id = development.getId();
        return DevelopmentResponse.from(
                development,
                role,
                stageProgressByDevelopment(List.of(id), organizationIds).getOrDefault(id, List.of()),
                unitCountByDevelopment(List.of(id), organizationIds).getOrDefault(id, 0L).intValue(),
                coverOf(development));
    }

    @Transactional
    public DevelopmentResponse create(UUID userId, CreateDevelopmentRequest request) {
        OrganizationRole role = membershipService.rolesOf(userId).get(request.organizationId());
        if (role == null) {
            // No pertenece a esa organización: no revelamos si existe.
            throw developmentNotFound();
        }
        if (role != OrganizationRole.OWNER) {
            throw DevelopmentAccessService.ownerOnly();
        }
        Development development = developmentRepository.save(new Development(
                request.organizationId(), request.name(), request.address(), request.kindOrDefault()));
        if (development.getKind() == DevelopmentKind.SINGLE) {
            // Compatibilidad: una obra única nace con su unidad.
            unitRepository.save(new Unit(development, SINGLE_UNIT_CODE, null));
        }
        return DevelopmentResponse.from(development, role, List.of(), development.getKind() == DevelopmentKind.SINGLE ? 1 : 0, null);
    }

    // --- Casas ---

    /**
     * Casas del desarrollo con su avance. Al dueño le llega cuántos compradores tiene cada
     * una y el precio; al encargado no, porque no ve datos de compradores ni comerciales.
     */
    @Transactional(readOnly = true)
    public List<UnitResponse> listUnits(UUID userId, UUID developmentId) {
        Development development = requireAccessibleDevelopment(userId, developmentId);
        List<UUID> organizationIds = membershipService.organizationIdsOf(userId);
        Optional<Set<UUID>> scope =
                developmentAccess.houseScope(userId, developmentId, development.getOrganizationId());
        List<Unit> units = unitRepository.findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds).stream()
                .filter(unit -> scope.map(houses -> houses.contains(unit.getId())).orElse(true))
                .toList();
        boolean owner = membershipService.isOwnerOf(userId, development.getOrganizationId());

        Map<UUID, List<StageProgress>> stagesByUnit = stageRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(developmentId, organizationIds)
                .stream()
                .filter(stage -> stage.getUnitId() != null)
                .map(StageCatalogService::toProgress)
                .collect(Collectors.groupingBy(StageProgress::unitId));

        Map<UUID, Long> buyers = !owner || units.isEmpty()
                ? Map.of()
                : unitMemberRepository
                        .findByUnitIdInAndOrganizationIdIn(units.stream().map(Unit::getId).toList(), organizationIds)
                        .stream()
                        .collect(Collectors.groupingBy(UnitMember::getUnitId, Collectors.counting()));

        ImageVariants.Images covers = imageVariants.lookup(List.of(development.getOrganizationId()),
                units.stream().map(Unit::getCoverStorageKey).toList());
        return units.stream()
                .map(unit -> UnitResponse.from(
                        unit,
                        owner ? buyers.getOrDefault(unit.getId(), 0L).intValue() : null,
                        progressOf(stagesByUnit.getOrDefault(unit.getId(), List.of())),
                        owner)
                        .withCover(covers.sign(unit.getCoverStorageKey())))
                .toList();
    }

    static UnitResponse.UnitProgress progressOf(List<StageProgress> stages) {
        return new UnitResponse.UnitProgress(
                StageCatalogService.overallProgressPercent(stages),
                StageCatalogService.currentStage(stages).map(StageProgress::name).orElse(null),
                stages.size());
    }

    /**
     * Alta de una casa, opcionalmente con las etapas estándar.
     *
     * <p>El código duplicado se ataja dos veces. La verificación previa produce el
     * mensaje que el usuario necesita leer; la captura de la violación de restricción
     * cubre la ventana entre esa verificación y el insert.
     */
    @Transactional
    public UnitResponse createUnit(UUID userId, UUID developmentId, CreateUnitRequest request) {
        Development development = requireOwnedDevelopment(userId, developmentId);
        if (development.getKind() == DevelopmentKind.SINGLE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Una obra única tiene una sola casa, que se crea con la obra");
        }
        String code = request.code().trim();
        if (unitRepository.existsByDevelopmentIdAndCode(developmentId, code)) {
            throw duplicateUnitCode(code);
        }
        Unit unit;
        try {
            unit = unitRepository.saveAndFlush(new Unit(development, code, request.description()));
        } catch (DataIntegrityViolationException e) {
            // El flush explícito es necesario: sin él, la violación saltaría al cerrar la
            // transacción, fuera de este try, y volvería a salir como 500.
            throw duplicateUnitCode(code);
        }
        List<StageProgress> stages = List.of();
        if (request.shouldApplyStandardStages()) {
            // La plantilla de casa trae las etapas estándar y los sectores del recorrido 3D.
            stages = unitStages.createStandardStages(unit).stream().map(DevelopmentService::toProgress).toList();
            captureSectors.applyStandard(unit);
        }
        return UnitResponse.from(unit, 0, progressOf(stages), true);
    }

    /**
     * Varias casas de una vez: un desarrollo de diez casas se carga en un paso. Los códigos
     * salen del prefijo y un número correlativo; si alguno ya existe no se crea ninguna.
     */
    @Transactional
    public List<UnitResponse> createUnits(UUID userId, UUID developmentId, CreateUnitsBatchRequest request) {
        requireOwnedDevelopment(userId, developmentId);
        String prefix = request.prefix().trim();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            codes.add(prefix + " " + (request.firstNumber() + i));
        }
        for (String code : codes) {
            if (unitRepository.existsByDevelopmentIdAndCode(developmentId, code)) {
                throw duplicateUnitCode(code);
            }
        }
        return codes.stream()
                .map(code -> createUnit(userId, developmentId,
                        new CreateUnitRequest(code, null, request.withStandardStages())))
                .toList();
    }

    /** Nombre y descripción corta de la casa. */
    @Transactional
    public UnitResponse updateUnit(UUID userId, UUID unitId, UpdateUnitRequest request) {
        Unit unit = unitRepository
                .findByIdAndOrganizationIdIn(unitId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));
        requireOwnedDevelopment(userId, unit.getDevelopment().getId());
        String code = request.code().trim();
        if (!code.equals(unit.getCode()) && unitRepository.existsByDevelopmentIdAndCode(unit.getDevelopment().getId(), code)) {
            throw duplicateUnitCode(code);
        }
        unit.rename(code, request.description());
        return UnitResponse.from(unit);
    }

    // --- Obras comunes ---

    /** Etapas de las obras comunes del desarrollo. Las de cada casa se piden por la casa. */
    @Transactional(readOnly = true)
    public List<StageResponse> listStages(UUID userId, UUID developmentId) {
        Development development = requireAccessibleDevelopment(userId, developmentId);
        boolean owner = membershipService.isOwnerOf(userId, development.getOrganizationId());
        return stageRepository
                .findByDevelopmentIdAndUnitIdIsNullAndOrganizationIdInOrderByOrderIndexAsc(
                        developmentId, membershipService.organizationIdsOf(userId))
                .stream()
                .map(stage -> StageResponse.forViewer(stage, owner))
                .toList();
    }

    @Transactional
    public StageResponse createStage(UUID userId, UUID developmentId, CreateStageRequest request) {
        Development development = requireOwnedDevelopment(userId, developmentId);
        if (stageRepository.existsByDevelopmentIdAndUnitIdIsNullAndOrderIndex(developmentId, request.orderIndex())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Ya existe una etapa con ese orden en el desarrollo");
        }
        try {
            Stage stage = stageRepository.saveAndFlush(
                    new Stage(development, request.name(), request.orderIndex()));
            return StageResponse.from(stage);
        } catch (DataIntegrityViolationException e) {
            // Misma ventana que en las casas: la verificación previa no es atómica.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Ya existe una etapa con ese orden en el desarrollo");
        }
    }

    @Transactional
    public StageResponse updateStageStatus(
            UUID userId, UUID stageId, UpdateStageStatusRequest request) {
        Stage stage = stageRepository
                .findByIdAndOrganizationIdIn(stageId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
        requireStageAccess(userId, stage);
        LocalDate today = LocalDate.now();
        if (request.effectiveOn() != null && request.effectiveOn().isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha del cambio de estado no puede ser futura");
        }
        stage.changeStatus(request.status(), request.effectiveOn() == null ? today : request.effectiveOn());
        return StageResponse.forViewer(
                stage, membershipService.isOwnerOf(userId, stage.getOrganizationId()));
    }

    // --- Común ---

    private static StageProgress toProgress(StageResponse stage) {
        return new StageProgress(stage.id(), stage.name(), stage.orderIndex(), stage.status().name(),
                stage.progressPercent(), stage.weight(), stage.unitId());
    }

    /** Etapas de varias obras en una sola consulta, agrupadas por obra y en su orden. */
    private Map<UUID, List<StageProgress>> stageProgressByDevelopment(
            List<UUID> developmentIds, Collection<UUID> organizationIds) {
        if (developmentIds.isEmpty()) {
            return Map.of();
        }
        return stageRepository.findByDevelopmentIdInAndOrganizationIdIn(developmentIds, organizationIds)
                .stream()
                .sorted(Comparator.comparingInt(Stage::getOrderIndex))
                .collect(Collectors.groupingBy(
                        stage -> stage.getDevelopment().getId(),
                        Collectors.mapping(StageCatalogService::toProgress, Collectors.toList())));
    }

    private Map<UUID, Long> unitCountByDevelopment(List<UUID> developmentIds, Collection<UUID> organizationIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (UUID developmentId : developmentIds) {
            counts.put(developmentId, (long) unitRepository.findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds).size());
        }
        return counts;
    }

    private SignedImage coverOf(Development development) {
        return imageVariants.lookup(List.of(development.getOrganizationId()), Collections.singletonList(development.getCoverStorageKey()))
                .sign(development.getCoverStorageKey());
    }

    private Development requireAccessibleDevelopment(UUID userId, UUID developmentId) {
        return developmentAccess.requireVisible(userId, developmentId);
    }

    /** Una etapa de una obra no asignada no existe para el encargado. */
    private void requireStageAccess(UUID userId, Stage stage) {
        if (!developmentAccess.canAccess(userId, stage.getDevelopment().getId(), stage.getOrganizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada");
        }
        developmentAccess.requireWorkOnUnit(userId, stage.getDevelopment().getId(), stage.getOrganizationId(), stage.getUnitId());
    }

    /** Administrar la estructura del desarrollo es tarea del dueño, no del encargado. */
    private Development requireOwnedDevelopment(UUID userId, UUID developmentId) {
        Development development = requireAccessibleDevelopment(userId, developmentId);
        if (!membershipService.isOwnerOf(userId, development.getOrganizationId())) {
            throw DevelopmentAccessService.ownerOnly();
        }
        return development;
    }

    private static ResponseStatusException duplicateUnitCode(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una casa con el nombre '" + code + "' en este desarrollo");
    }

    private static ResponseStatusException developmentNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Desarrollo no encontrado");
    }
}
