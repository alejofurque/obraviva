package com.obraviva.platform.developments;

import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Declaración de avance físico, peso y presupuesto de una etapa.
 *
 * <p>Sólo el equipo de la obra declara: un comprador ve el avance en su línea de
 * tiempo, no lo fija.
 */
@Service
public class StagePlanService {

    private static final Logger log = LoggerFactory.getLogger(StagePlanService.class);

    private final StageRepository stageRepository;
    private final OrganizationMembershipService membershipService;
    private final DevelopmentAccessService developmentAccess;

    public StagePlanService(
            StageRepository stageRepository, OrganizationMembershipService membershipService,
            DevelopmentAccessService developmentAccess) {
        this.stageRepository = stageRepository;
        this.membershipService = membershipService;
        this.developmentAccess = developmentAccess;
    }

    @Transactional
    public StageResponse updatePlan(UUID userId, UUID stageId, UpdateStagePlanRequest request) {
        Stage stage = stageRepository
                .findByIdAndOrganizationIdIn(stageId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
        if (!developmentAccess.canAccess(userId, stage.getDevelopment().getId(), stage.getOrganizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada");
        }
        developmentAccess.requireWorkOnUnit(userId, stage.getDevelopment().getId(), stage.getOrganizationId(), stage.getUnitId());

        // Declarar el avance es tarea del encargado; el peso y el presupuesto son
        // planificación económica y quedan para el dueño.
        boolean owner = membershipService.isOwnerOf(userId, stage.getOrganizationId());
        boolean touchesPlan = request.name() != null
                || request.weight() != null
                || request.budgetAmount() != null
                || request.shouldClearBudget()
                || request.description() != null
                || request.plannedStart() != null
                || request.plannedEnd() != null
                || request.shouldClearPlannedDates();
        if (touchesPlan && !owner) {
            throw DevelopmentAccessService.ownerOnly();
        }

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La etapa tiene que tener nombre");
            }
            stage.rename(request.name());
        }

        // Sólo se aplica lo que vino: declarar el avance semanal no obliga a repetir
        // el presupuesto.
        if (request.progressPercent() != null) {
            stage.declareProgress(request.progressPercent());
        }
        if (request.weight() != null) {
            stage.changeWeight(request.weight());
        }
        if (request.shouldClearBudget()) {
            stage.changeBudget(null);
        } else if (request.budgetAmount() != null) {
            stage.changeBudget(request.budgetAmount());
        }
        if (request.description() != null) {
            stage.describe(request.description());
        }
        try {
            if (request.shouldClearPlannedDates()) {
                stage.plan(null, null);
            } else if (request.plannedStart() != null || request.plannedEnd() != null) {
                stage.plan(
                        request.plannedStart() != null ? request.plannedStart() : stage.getPlannedStart(),
                        request.plannedEnd() != null ? request.plannedEnd() : stage.getPlannedEnd());
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        log.info("Plan de la etapa {} actualizado por {}: avance {}%, peso {}",
                stageId, userId, stage.getProgressPercent(), stage.getWeight());

        return StageResponse.forViewer(stage, owner);
    }
}