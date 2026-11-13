package com.obraviva.platform.overview;

import com.obraviva.platform.captures3d.Capture3dService;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureProcessingResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureResponse;
import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.DevelopmentResponse;
import com.obraviva.platform.developments.DevelopmentService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.StageCatalogService.StageProgress;
import com.obraviva.platform.developments.UnitStageService;
import com.obraviva.platform.developments.UnitResponse;
import com.obraviva.platform.progress.ProgressEntryResponse;
import com.obraviva.platform.progress.ProgressService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * El trabajo de campo de una persona: las casas en las que carga, con lo que necesita
 * para decidir qué actualizar hoy. Es la pantalla del encargado en el celular.
 *
 * <p>Junta lo que exponen los servicios de cada módulo llamándolos con el usuario, así que
 * un encargado de casas puntuales recibe sólo las suyas.
 */
@Service
public class FieldWorkService {

    private final DevelopmentAccessService developmentAccess;
    private final DevelopmentService developments;
    private final ProgressService progress;
    private final Capture3dService captures;
    private final CaptureSectorService sectors;
    private final UnitStageService unitStages;

    public FieldWorkService(
            DevelopmentAccessService developmentAccess,
            DevelopmentService developments,
            ProgressService progress,
            Capture3dService captures,
            CaptureSectorService sectors,
            UnitStageService unitStages) {
        this.developmentAccess = developmentAccess;
        this.developments = developments;
        this.progress = progress;
        this.captures = captures;
        this.sectors = sectors;
        this.unitStages = unitStages;
    }

    /**
     * Una casa para trabajar. {@code capturedSectors} cuenta los sectores con recorrido en la
     * última fecha grabada, contra {@code sectors}: si faltan, ese recorrido está incompleto.
     */
    public record AssignedHouse(
            UUID developmentId,
            String developmentName,
            UUID unitId,
            String code,
            String modelName,
            Integer progressPercent,
            String currentStageName,
            /** Avance de la etapa en curso, la misma que nombra currentStageName; nulo si no hay. */
            Integer currentStagePercent,
            Integer stageCount,
            String coverUrl,
            /** La miniatura de la portada, nula mientras no esté: se usa el original. */
            String coverThumbUrl,
            List<String> sectors,
            LocalDate lastEntryDate,
            String lastEntryText,
            LocalDate lastCaptureDate,
            int capturedSectors,
            int capturesInProcess) {
    }

    public List<AssignedHouse> myHouses(UUID userId) {
        List<AssignedHouse> houses = new ArrayList<>();
        for (UUID developmentId : developmentAccess.visibleDevelopmentIds(userId)) {
            DevelopmentResponse development = developments.detail(userId, developmentId);
            List<UnitResponse> units = developments.listUnits(userId, developmentId);
            if (units.isEmpty()) {
                continue;
            }
            List<ProgressEntryResponse> entries = progress.timeline(userId, developmentId);
            List<CaptureResponse> available = captures.listAvailable(userId, developmentId);
            List<CaptureProcessingResponse> processing = captures.listProcessing(userId, developmentId);
            for (UnitResponse unit : units) {
                houses.add(house(userId, development, unit, entries, available, processing));
            }
        }
        houses.sort(Comparator.comparing(AssignedHouse::developmentName)
                .thenComparing(AssignedHouse::code, FieldWorkService::naturalOrder));
        return houses;
    }

    private AssignedHouse house(UUID userId, DevelopmentResponse development, UnitResponse unit,
                                List<ProgressEntryResponse> entries, List<CaptureResponse> available,
                                List<CaptureProcessingResponse> processing) {
        ProgressEntryResponse last = entries.stream()
                .filter(entry -> unit.id().equals(entry.unitId()))
                .max(Comparator.comparing(ProgressEntryResponse::entryDate))
                .orElse(null);
        List<CaptureResponse> own = available.stream().filter(capture -> unit.id().equals(capture.unitId())).toList();
        LocalDate lastCapture = own.stream().map(CaptureResponse::captureDate).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        List<String> houseSectors = sectors.list(userId, unit.id());
        Integer stagePercent = StageCatalogService.currentStage(unitStages.list(userId, unit.id()).stream()
                        .map(stage -> new StageProgress(stage.id(), stage.name(), stage.orderIndex(), stage.status().name(),
                                stage.progressPercent(), stage.weight(), stage.unitId()))
                        .toList())
                .map(StageProgress::progressPercent)
                .orElse(null);
        Set<String> captured = new HashSet<>();
        own.stream().filter(capture -> lastCapture != null && lastCapture.equals(capture.captureDate()))
                .map(CaptureResponse::zone).filter(Objects::nonNull).forEach(captured::add);
        int capturedSectors = (int) houseSectors.stream().filter(captured::contains).count();
        int inProcess = (int) processing.stream()
                .filter(capture -> unit.id().equals(capture.unitId()) && !"FAILED".equals(capture.jobStatus()))
                .count();
        return new AssignedHouse(
                development.id(), development.name(), unit.id(), unit.code(), unit.modelName(),
                unit.progressPercent(), unit.currentStageName(), stagePercent, unit.stageCount(), unit.coverUrl(), unit.coverThumbUrl(),
                houseSectors,
                last == null ? null : last.entryDate(),
                last == null ? null : last.description(),
                lastCapture, capturedSectors, inProcess);
    }

    /** "Casa 2" antes que "Casa 10". */
    private static int naturalOrder(String a, String b) {
        String digitsA = a.replaceAll("\\D", "");
        String digitsB = b.replaceAll("\\D", "");
        String prefixA = a.replaceAll("\\d", "");
        String prefixB = b.replaceAll("\\d", "");
        if (prefixA.equals(prefixB) && !digitsA.isEmpty() && !digitsB.isEmpty() && digitsA.length() < 10 && digitsB.length() < 10) {
            return Integer.compare(Integer.parseInt(digitsA), Integer.parseInt(digitsB));
        }
        return a.compareTo(b);
    }
}
