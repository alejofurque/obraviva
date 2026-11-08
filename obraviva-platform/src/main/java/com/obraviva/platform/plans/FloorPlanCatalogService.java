package com.obraviva.platform.plans;

import com.obraviva.platform.plans.FloorPlanDtos.FloorPlanResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * API pública del módulo de planos para la vista de una unidad. No verifica acceso:
 * recibe la organización ya resuelta por quien llama, igual que los demás catálogos.
 */
@Service
public class FloorPlanCatalogService {

    private final FloorPlanRepository planRepository;

    public FloorPlanCatalogService(FloorPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    /**
     * Planta con la clave de su imagen. La clave no sale al cliente: quien la recibe firma
     * una URL con la vigencia que corresponda a su contexto.
     */
    public record CatalogPlan(FloorPlanResponse plan, String imageStorageKey) {
    }

    @Transactional(readOnly = true)
    public List<CatalogPlan> plansOf(UUID unitId, UUID organizationId) {
        return planRepository
                .findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(unitId, List.of(organizationId))
                .stream()
                .map(plan -> new CatalogPlan(FloorPlanService.toResponse(plan, null), plan.getImageStorageKey()))
                .toList();
    }
}
