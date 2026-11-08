package com.obraviva.platform.developments;

import com.obraviva.platform.developments.ExpenseDtos.CreateExpenseRequest;
import com.obraviva.platform.developments.ExpenseDtos.ExpenseResponse;
import com.obraviva.platform.developments.ExpenseDtos.UpdateExpenseRequest;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Carga y consulta de gastos. Información privada del desarrollista: no aparece en la
 * vista pública ni en "Mi propiedad" bajo ninguna circunstancia.
 */
@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final StageRepository stageRepository;
    private final UnitRepository unitRepository;
    private final DevelopmentAccessService developmentAccess;
    private final OrganizationMembershipService membershipService;

    public ExpenseService(
            ExpenseRepository expenseRepository,
            StageRepository stageRepository,
            UnitRepository unitRepository,
            DevelopmentAccessService developmentAccess,
            OrganizationMembershipService membershipService) {
        this.expenseRepository = expenseRepository;
        this.stageRepository = stageRepository;
        this.unitRepository = unitRepository;
        this.developmentAccess = developmentAccess;
        this.membershipService = membershipService;
    }

    @Transactional
    public ExpenseResponse create(UUID userId, UUID developmentId, CreateExpenseRequest request) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);

        requireStageOf(userId, developmentId, request.unitId(), request.stageId());
        if (request.unitId() != null && unitRepository.findByIdAndOrganizationIdIn(request.unitId(), List.of(organizationId))
                .filter(unit -> unit.getDevelopment().getId().equals(developmentId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La casa no pertenece a esta obra");
        }

        Expense created = new Expense(
                organizationId,
                developmentId,
                request.stageId(),
                request.incurredOn(),
                request.amount(),
                request.description(),
                userId);
        created.assignUnit(request.unitId());
        Expense expense = expenseRepository.save(created);

        return ExpenseResponse.from(expense);
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> listByDevelopment(UUID userId, UUID developmentId) {
        developmentAccess.requireOwnerAccess(userId, developmentId);
        return expenseRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByIncurredOnAsc(
                        developmentId, membershipService.organizationIdsOf(userId))
                .stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    /**
     * Corrige un gasto con las mismas reglas que el alta: lo hace el dueño, el gasto de otra
     * organización no existe (404) y la etapa tiene que ser de la misma casa del gasto, o de
     * las obras comunes del mismo desarrollo si el gasto no tiene casa.
     */
    @Transactional
    public ExpenseResponse update(UUID userId, UUID expenseId, UpdateExpenseRequest request) {
        Expense expense = requireExpense(userId, expenseId);
        developmentAccess.requireOwnerOf(userId, expense.getOrganizationId());
        requireStageOf(userId, expense.getDevelopmentId(), expense.getUnitId(), request.stageId());

        expense.update(request.stageId(), request.incurredOn(), request.amount(), blankToNull(request.description()));
        return ExpenseResponse.from(expense);
    }

    @Transactional
    public void delete(UUID userId, UUID expenseId) {
        Expense expense = requireExpense(userId, expenseId);
        developmentAccess.requireOwnerOf(userId, expense.getOrganizationId());
        expenseRepository.delete(expense);
    }

    private Expense requireExpense(UUID userId, UUID expenseId) {
        return expenseRepository
                .findByIdAndOrganizationIdIn(expenseId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado"));
    }

    /**
     * La etapa de un gasto se verifica contra la organización: un identificador de otra obra
     * imputaría el gasto a un presupuesto ajeno y falsearía su semáforo. Tiene que ser del
     * mismo desarrollo y de la misma casa del gasto (común, si el gasto no tiene casa).
     */
    private void requireStageOf(UUID userId, UUID developmentId, UUID unitId, UUID stageId) {
        if (stageId == null) {
            return;
        }
        Stage stage = stageRepository
                .findByIdAndOrganizationIdIn(stageId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
        if (!stage.getDevelopment().getId().equals(developmentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La etapa pertenece a otro desarrollo");
        }
        if (!java.util.Objects.equals(stage.getUnitId(), unitId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La etapa no es de la casa del gasto");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}