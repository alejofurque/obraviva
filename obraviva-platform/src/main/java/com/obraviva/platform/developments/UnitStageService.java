package com.obraviva.platform.developments;

import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Etapas de una casa. Cada casa se construye por separado: tiene su propio cronograma,
 * su avance y su presupuesto.
 *
 * <p>Estructurar (crear etapas, aplicar la plantilla) es del dueño. Declarar avance y
 * estado lo hacen dueño y encargado con {@code StagePlanService} y
 * {@code DevelopmentService#updateStageStatus}, igual que en las obras comunes.
 */
@Service
public class UnitStageService {

    private final UnitRepository unitRepository;
    private final StageRepository stageRepository;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;

    public UnitStageService(
            UnitRepository unitRepository,
            StageRepository stageRepository,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService) {
        this.unitRepository = unitRepository;
        this.stageRepository = stageRepository;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
    }

    @Transactional(readOnly = true)
    public List<StageResponse> list(UUID userId, UUID unitId) {
        Unit unit = requireAccessibleUnit(userId, unitId);
        boolean owner = membershipService.isOwnerOf(userId, unit.getOrganizationId());
        return stageRepository
                .findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(unitId, List.of(unit.getOrganizationId()))
                .stream()
                .map(stage -> StageResponse.forViewer(stage, owner))
                .toList();
    }

    @Transactional
    public StageResponse create(UUID userId, UUID unitId, CreateStageRequest request) {
        Unit unit = requireOwnedUnit(userId, unitId);
        if (stageRepository.existsByUnitIdAndOrderIndex(unitId, request.orderIndex())) {
            throw duplicateOrder();
        }
        try {
            return StageResponse.from(stageRepository.saveAndFlush(newStage(unit, request.name(), request.orderIndex())));
        } catch (DataIntegrityViolationException e) {
            throw duplicateOrder();
        }
    }

    /**
     * Carga las etapas estándar de una casa. Sólo sobre una casa sin etapas: aplicarla
     * encima de etapas existentes mezclaría dos cronogramas.
     */
    @Transactional
    public List<StageResponse> applyStandardStages(UUID userId, UUID unitId) {
        Unit unit = requireOwnedUnit(userId, unitId);
        return createStandardStages(unit);
    }

    /** Para quien ya verificó al dueño: el alta de casas con plantilla. */
    List<StageResponse> createStandardStages(Unit unit) {
        if (!stageRepository.findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(unit.getId(), List.of(unit.getOrganizationId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La casa ya tiene etapas: la plantilla se aplica sólo a una casa sin etapas");
        }
        List<StandardHouseStages.StandardStage> template = StandardHouseStages.STAGES;
        return java.util.stream.IntStream.range(0, template.size())
                .mapToObj(index -> {
                    StandardHouseStages.StandardStage standard = template.get(index);
                    Stage stage = newStage(unit, standard.name(), index + 1);
                    stage.describe(standard.description());
                    stage.changeWeight(standard.weight());
                    return StageResponse.from(stageRepository.save(stage));
                })
                .toList();
    }

    private static Stage newStage(Unit unit, String name, int orderIndex) {
        Stage stage = new Stage(unit.getDevelopment(), name.trim(), orderIndex);
        stage.assignUnit(unit.getId());
        return stage;
    }

    /** Desarrollo de una casa que el usuario puede ver; 404 si no. */
    @Transactional(readOnly = true)
    public UUID developmentOf(UUID userId, UUID unitId) {
        return requireAccessibleUnit(userId, unitId).getDevelopment().getId();
    }

    private Unit requireAccessibleUnit(UUID userId, UUID unitId) {
        return unitRepository
                .findByIdAndOrganizationIdIn(unitId, membershipService.organizationIdsOf(userId))
                .filter(unit -> developmentAccess.canAccess(userId, unit.getDevelopment().getId(), unit.getOrganizationId()))
                // Un encargado de casas puntuales no ve las etapas de las demás.
                .filter(unit -> developmentAccess.canWorkOnUnit(
                        userId, unit.getDevelopment().getId(), unit.getOrganizationId(), unit.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));
    }

    private Unit requireOwnedUnit(UUID userId, UUID unitId) {
        Unit unit = requireAccessibleUnit(userId, unitId);
        if (!membershipService.isOwnerOf(userId, unit.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return unit;
    }

    private static ResponseStatusException duplicateOrder() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una etapa con ese orden en la casa");
    }
}
