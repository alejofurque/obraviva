package com.obraviva.platform.developments;

import com.obraviva.platform.developments.StageCatalogService.StageProgress;
import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.organizations.OrganizationRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Desarrollo visto por un usuario concreto: {@code role} es el suyo en la organización
 * dueña. Orienta a la interfaz sobre qué ofrecer; no reemplaza las verificaciones del
 * servidor.
 *
 * <p>El avance del desarrollo es el promedio del avance de sus casas; si todavía no tiene
 * casas con etapas, el de sus obras comunes. {@code currentStageName} es la etapa en curso
 * de las obras comunes. {@code coverUrl} viene firmada, con su miniatura y su versión de
 * pantalla, nulas mientras no estén.
 *
 * <p>{@code restrictedToUnitIds} es nulo si el usuario trabaja en todo el desarrollo (el
 * dueño, o un encargado del desarrollo entero). Con valor, son las únicas casas en las que
 * carga: un encargado de casas puntuales, que no carga en las obras comunes. En ese caso
 * {@code unitCount} cuenta sus casas y no las del barrio.
 */
public record DevelopmentResponse(
        UUID id,
        UUID organizationId,
        String name,
        String address,
        DevelopmentKind kind,
        OrganizationRole role,
        int overallProgressPercent,
        String currentStageName,
        int unitCount,
        int completedUnitCount,
        String description,
        String locality,
        String province,
        DevelopmentType developmentType,
        BigDecimal landAreaM2,
        LocalDate startedOn,
        YearMonth estimatedDelivery,
        List<String> amenities,
        List<String> services,
        String architect,
        String builder,
        String permitNumber,
        String coverUrl,
        String coverThumbUrl,
        String coverScreenUrl,
        Instant createdAt,
        List<UUID> restrictedToUnitIds) {

    static DevelopmentResponse from(
            Development development, OrganizationRole role, List<StageProgress> stages, int unitCount, SignedImage cover) {
        List<StageProgress> common = stages.stream().filter(stage -> stage.unitId() == null).toList();
        return new DevelopmentResponse(
                development.getId(),
                development.getOrganizationId(),
                development.getName(),
                development.getAddress(),
                development.getKind(),
                role,
                StageCatalogService.developmentProgressPercent(stages),
                StageCatalogService.currentStage(common).map(StageProgress::name).orElse(null),
                unitCount,
                StageCatalogService.completedUnitCount(stages),
                development.getDescription(),
                development.getLocality(),
                development.getProvince(),
                development.getDevelopmentType(),
                development.getLandAreaM2(),
                development.getStartedOn(),
                development.getEstimatedDelivery() == null ? null : YearMonth.from(development.getEstimatedDelivery()),
                development.getAmenities(),
                development.getServices(),
                development.getArchitect(),
                development.getBuilder(),
                development.getPermitNumber(),
                cover == null ? null : cover.url(),
                cover == null ? null : cover.thumbUrl(),
                cover == null ? null : cover.screenUrl(),
                development.getCreatedAt(),
                null);
    }

    /** El mismo desarrollo visto por un encargado de casas puntuales. */
    DevelopmentResponse restrictedTo(List<UUID> unitIds, List<StageProgress> stages) {
        // El avance y las casas terminadas salen de sus casas: el de todo el barrio
        // dejaría ver cómo van las casas que no tiene.
        List<StageProgress> visible = stages.stream()
                .filter(stage -> stage.unitId() == null || unitIds.contains(stage.unitId()))
                .toList();
        return new DevelopmentResponse(id, organizationId, name, address, kind, role,
                StageCatalogService.developmentProgressPercent(visible),
                currentStageName, unitIds.size(), StageCatalogService.completedUnitCount(visible), description, locality, province,
                developmentType, landAreaM2, startedOn, estimatedDelivery, amenities, services, architect,
                builder, permitNumber, coverUrl, coverThumbUrl, coverScreenUrl, createdAt, unitIds);
    }
}
