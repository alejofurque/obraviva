package com.obraviva.platform.developments;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública del módulo developments para consultar unidades desde otros módulos,
 * como proyecciones y sin filtrar entidades JPA.
 *
 * <p>Existe por el mismo motivo que {@code StageCatalogService}: la vista pública
 * necesita el código y la descripción de una unidad, y ningún módulo accede a las
 * entidades de otro.
 */
@Service
public class UnitCatalogService {

    private final UnitRepository unitRepository;
    private final CaptureSectorRepository sectorRepository;

    public UnitCatalogService(UnitRepository unitRepository, CaptureSectorRepository sectorRepository) {
        this.unitRepository = unitRepository;
        this.sectorRepository = sectorRepository;
    }

    /**
     * Unidad vista desde fuera del módulo, con los datos de su desarrollo y su ficha.
     *
     * <p>{@code coverStorageKey} es una clave interna: quien la recibe firma una URL con
     * la vigencia de su contexto y nunca la entrega tal cual.
     */
    public record UnitSummary(
            UUID id,
            UUID developmentId,
            String code,
            String description,
            String developmentName,
            String developmentAddress,
            DevelopmentKind developmentKind,
            UUID organizationId,
            BigDecimal areaM2,
            Integer bedrooms,
            Integer bathrooms,
            List<String> features,
            YearMonth estimatedDelivery,
            String coverStorageKey,
            /** El render del frente de la casa, nulo si no se cargó. Se firma al usarlo. */
            String frontRenderStorageKey,
            SheetSummary sheet,
            DevelopmentInfo development,
            /** Sectores del recorrido 3D, en el orden en que se recorren. */
            List<String> sectors) {
    }

    /** Ficha de venta sin precio: lo que se puede mostrar al comprador y en un enlace. */
    public record SheetSummary(
            String modelName, BigDecimal lotAreaM2, BigDecimal coveredAreaM2, BigDecimal semiCoveredAreaM2,
            Integer rooms, Integer toilettes, Integer floors, Integer parkingSpaces, String orientation,
            String longDescription, String finishes) {
    }

    /** Datos del desarrollo que ve el comprador. {@code coverStorageKey} se firma al usarla. */
    public record DevelopmentInfo(
            String description, String locality, String province, String developmentType, BigDecimal landAreaM2,
            List<String> amenities, List<String> services, String architect, String builder,
            YearMonth estimatedDelivery, String coverStorageKey) {
    }

    /**
     * Busca una unidad dentro de una organización.
     *
     * <p>Recibe la organización ya resuelta en vez del usuario porque quien llama
     * puede no tener usuario: la vista pública se autentica con un token de enlace, y
     * la organización sale de ese enlace. La búsqueda queda igualmente acotada.
     */
    @Transactional(readOnly = true)
    public Optional<UnitSummary> findInOrganization(UUID unitId, UUID organizationId) {
        return unitRepository
                .findByIdAndOrganizationIdIn(unitId, List.of(organizationId))
                .map(unit -> new UnitSummary(
                        unit.getId(),
                        unit.getDevelopment().getId(),
                        unit.getCode(),
                        unit.getDescription(),
                        unit.getDevelopment().getName(),
                        unit.getDevelopment().getAddress(),
                        unit.getDevelopment().getKind(),
                        unit.getOrganizationId(),
                        unit.getAreaM2(),
                        unit.getBedrooms(),
                        unit.getBathrooms(),
                        unit.getFeatures(),
                        unit.getEstimatedDelivery(),
                        unit.getCoverStorageKey(),
                        unit.getFrontRenderStorageKey(),
                        new SheetSummary(
                                unit.getModelName(), unit.getLotAreaM2(), unit.getCoveredAreaM2(), unit.getSemiCoveredAreaM2(),
                                unit.getRooms(), unit.getToilettes(), unit.getFloors(), unit.getParkingSpaces(),
                                unit.getOrientation(), unit.getLongDescription(), unit.getFinishes()),
                        developmentInfo(unit.getDevelopment()),
                        sectorRepository.findByUnitIdOrderByOrderIndexAsc(unit.getId()).stream()
                                .map(CaptureSector::getName).toList()));
    }

    private static DevelopmentInfo developmentInfo(Development development) {
        return new DevelopmentInfo(
                development.getDescription(),
                development.getLocality(),
                development.getProvince(),
                development.getDevelopmentType() == null ? null : development.getDevelopmentType().name(),
                development.getLandAreaM2(),
                development.getAmenities(),
                development.getServices(),
                development.getArchitect(),
                development.getBuilder(),
                development.getEstimatedDelivery() == null ? null : YearMonth.from(development.getEstimatedDelivery()),
                development.getCoverStorageKey());
    }
}