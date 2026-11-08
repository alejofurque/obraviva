package com.obraviva.platform.plans;

import com.obraviva.platform.developments.UnitAccessService;
import com.obraviva.platform.developments.UnitAccessService.UnitAccess;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.plans.FloorPlan.RoomSpec;
import com.obraviva.platform.plans.FloorPlanDtos.CreateFloorPlanRequest;
import com.obraviva.platform.plans.FloorPlanDtos.FloorPlanResponse;
import com.obraviva.platform.plans.FloorPlanDtos.ReplaceRoomsRequest;
import com.obraviva.platform.plans.FloorPlanDtos.RoomRequest;
import com.obraviva.platform.plans.FloorPlanDtos.RoomResponse;
import com.obraviva.platform.plans.FloorPlanDtos.UpdateFloorPlanRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Planos de una unidad. Los ve cualquiera con acceso a la unidad; los edita el dueño
 * del desarrollo.
 */
@Service
public class FloorPlanService {

    private final FloorPlanRepository planRepository;
    private final UnitAccessService unitAccess;
    private final OrganizationMembershipService membershipService;
    private final StorageService storageService;

    public FloorPlanService(
            FloorPlanRepository planRepository,
            UnitAccessService unitAccess,
            OrganizationMembershipService membershipService,
            StorageService storageService) {
        this.planRepository = planRepository;
        this.unitAccess = unitAccess;
        this.membershipService = membershipService;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public List<FloorPlanResponse> list(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);
        return planRepository
                .findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(
                        unitId, List.of(access.unit().organizationId()))
                .stream()
                .map(this::signed)
                .toList();
    }

    @Transactional
    public FloorPlanResponse create(UUID userId, UUID unitId, CreateFloorPlanRequest request) {
        UnitAccess access = requireOwnerAccess(userId, unitId);
        UUID organizationId = access.unit().organizationId();

        int nextOrder = planRepository
                .findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(unitId, List.of(organizationId))
                .stream()
                .mapToInt(FloorPlan::getOrderIndex)
                .max()
                .orElse(-1) + 1;

        FloorPlan plan = planRepository.save(new FloorPlan(
                organizationId, unitId, request.name().trim(), nextOrder,
                request.widthM(), request.depthM()));
        return signed(plan);
    }

    @Transactional
    public FloorPlanResponse update(UUID userId, UUID planId, UpdateFloorPlanRequest request) {
        FloorPlan plan = requireOwnedPlan(userId, planId);
        try {
            plan.update(request.name().trim(), request.widthM(), request.depthM(), request.orderIndex());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return signed(plan);
    }

    @Transactional
    public FloorPlanResponse replaceRooms(UUID userId, UUID planId, ReplaceRoomsRequest request) {
        FloorPlan plan = requireOwnedPlan(userId, planId);

        List<RoomSpec> specs = request.rooms().stream().map(FloorPlanService::toSpec).toList();
        try {
            plan.replaceRooms(specs);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return signed(plan);
    }

    @Transactional
    public void delete(UUID userId, UUID planId) {
        planRepository.delete(requireOwnedPlan(userId, planId));
    }

    // --- Interno ---

    private static RoomSpec toSpec(RoomRequest room) {
        if (room.polygon().size() % 2 != 0 || room.polygon().stream().anyMatch(value -> value == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El contorno de '" + room.name() + "' tiene que ser una lista de pares x, y");
        }
        double[] polygon = room.polygon().stream().mapToDouble(Double::doubleValue).toArray();
        if (!PlanGeometry.allFinite(polygon)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El contorno de '" + room.name() + "' tiene coordenadas inválidas");
        }
        return new RoomSpec(room.name().trim(), polygon, room.declaredAreaM2());
    }

    private UnitAccess requireOwnerAccess(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);
        if (access.isBuyer()) {
            // Para el comprador, la edición de la unidad no existe.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada");
        }
        if (!membershipService.isOwnerOf(userId, access.unit().organizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return access;
    }

    private FloorPlan requireOwnedPlan(UUID userId, UUID planId) {
        FloorPlan plan = planRepository
                .findByIdAndOrganizationIdIn(planId, membershipService.organizationIdsOf(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Planta no encontrada"));
        if (!membershipService.isOwnerOf(userId, plan.getOrganizationId())) {
            throw OrganizationMembershipService.ownerOnly();
        }
        return plan;
    }

    private FloorPlanResponse signed(FloorPlan plan) {
        return toResponse(plan, plan.getImageStorageKey() == null ? null : storageService.presignDownload(plan.getImageStorageKey()));
    }

    /** La URL de la imagen la firma quien llama, con la vigencia de su contexto. */
    static FloorPlanResponse toResponse(FloorPlan plan, String imageUrl) {
        return new FloorPlanResponse(
                plan.getId(),
                plan.getUnitId(),
                plan.getName(),
                plan.getOrderIndex(),
                plan.getWidthM(),
                plan.getDepthM(),
                plan.getRooms().stream()
                        .map(room -> new RoomResponse(
                                room.getId(),
                                room.getName(),
                                Arrays.stream(room.getPolygon()).boxed().toList(),
                                room.areaM2(),
                                room.hasDeclaredArea()))
                        .toList(),
                imageUrl,
                plan.getImageWidthPx(),
                plan.getImageHeightPx());
    }
}
