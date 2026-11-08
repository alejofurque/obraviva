package com.obraviva.platform.captures3d;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API pública del módulo captures3d hacia otros módulos: expone las capturas como
 * proyecciones, sin filtrar entidades JPA fuera del módulo.
 *
 * <p>Existe por el mismo motivo que {@code StageCatalogService} en developments: el
 * motor de reportes y la vista pública necesitan saber qué capturas hay, y ningún
 * módulo accede a las entidades de otro. Se mantiene separado de
 * {@code Capture3dService} —que atiende las peticiones del visor y el pipeline— para
 * que la superficie que ven los demás módulos sea explícita y mínima.
 */
@Service
public class CaptureCatalogService {

    private final Capture3dRepository captureRepository;

    public CaptureCatalogService(Capture3dRepository captureRepository) {
        this.captureRepository = captureRepository;
    }

    /** Recorridos publicados de un sector de una casa: lo que el dueño tendría que mover antes de quitarlo. */
    @Transactional(readOnly = true)
    public long countInZone(UUID organizationId, UUID unitId, String zone) {
        return captureRepository.countByOrganizationIdAndUnitIdAndZoneAndStatus(
                organizationId, unitId, zone, CaptureStatus.AVAILABLE);
    }

    /** Renombre de un sector en los recorridos de esa casa, en la transacción de quien renombra. */
    @Transactional
    public int renameZone(UUID organizationId, UUID unitId, String from, String to) {
        return captureRepository.renameZone(organizationId, unitId, from, to);
    }

    /** Captura vista desde fuera del módulo. */
    public record CaptureSummary(UUID id, LocalDate captureDate, String description) {
    }

    /**
     * Captura lista para renderizar: incluye la clave de almacenamiento y la
     * transformación espacial.
     *
     * <p>La clave no se expone nunca al cliente; quien recibe esto la usa para firmar
     * una URL con la vigencia que corresponda a su contexto.
     */
    public record RenderableCapture(
            LocalDate captureDate,
            String description,
            String zone,
            UUID unitId,
            UUID stageId,
            String storageKey,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double scale) {
    }

    /**
     * Capturas navegables del período. No verifica acceso: recibe las organizaciones
     * ya resueltas por el llamador y la búsqueda queda acotada en la firma del
     * repositorio.
     */
    @Transactional(readOnly = true)
    public List<CaptureSummary> availableCapturesInPeriod(
            UUID developmentId, List<UUID> organizationIds, LocalDate from, LocalDate to) {
        return captureRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusAndCaptureDateBetweenOrderByCaptureDateAsc(
                        developmentId, organizationIds, CaptureStatus.AVAILABLE, from, to)
                .stream()
                .map(capture -> new CaptureSummary(
                        capture.getId(), capture.getCaptureDate(), capture.getDescription()))
                .toList();
    }

    /**
     * Capturas disponibles en orden cronológico, con lo necesario para renderizarlas.
     * Es el insumo del viaje en el tiempo.
     */
    @Transactional(readOnly = true)
    public List<RenderableCapture> renderableCaptures(
            UUID developmentId, List<UUID> organizationIds) {
        return captureRepository
                .findByDevelopmentIdAndOrganizationIdInAndStatusOrderByCaptureDateAsc(
                        developmentId, organizationIds, CaptureStatus.AVAILABLE)
                .stream()
                .map(capture -> new RenderableCapture(
                        capture.getCaptureDate(),
                        capture.getDescription(),
                        capture.getZone(),
                        capture.getUnitId(),
                        capture.getStageId(),
                        capture.getStorageKey(),
                        capture.getTransform().getPositionX(),
                        capture.getTransform().getPositionY(),
                        capture.getTransform().getPositionZ(),
                        capture.getTransform().getRotationX(),
                        capture.getTransform().getRotationY(),
                        capture.getTransform().getRotationZ(),
                        capture.getTransform().getScale()))
                .toList();
    }
}