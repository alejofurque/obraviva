package com.obraviva.platform.overview;

import com.obraviva.platform.activity.ActivityDtos.ActivityItem;
import com.obraviva.platform.activity.ActivityService;
import com.obraviva.platform.ai.AudioDtos.ExtractionSummaryResponse;
import com.obraviva.platform.ai.AudioExtractionService;
import com.obraviva.platform.captures3d.Capture3dService;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureProcessingResponse;
import com.obraviva.platform.captures3d.CaptureDtos.CaptureResponse;
import com.obraviva.platform.developments.DashboardDtos.DashboardResponse;
import com.obraviva.platform.developments.DashboardDtos.TrafficLight;
import com.obraviva.platform.developments.DashboardDtos.UnitDashboardRow;
import com.obraviva.platform.developments.DashboardService;
import com.obraviva.platform.developments.DevelopmentResponse;
import com.obraviva.platform.developments.DevelopmentService;
import com.obraviva.platform.developments.DevelopmentTeamDtos.TeamPerson;
import com.obraviva.platform.developments.DevelopmentTeamService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.StageCatalogService.StageProgress;
import com.obraviva.platform.developments.StageResponse;
import com.obraviva.platform.developments.StageStatus;
import com.obraviva.platform.developments.UnitResponse;
import com.obraviva.platform.developments.UnitSalesStatus;
import com.obraviva.platform.developments.UnitStageService;
import com.obraviva.platform.media.MediaAssetResponse;
import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.media.MediaService;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.overview.OverviewDtos.CommercialOverview;
import com.obraviva.platform.overview.OverviewDtos.ContentOverview;
import com.obraviva.platform.overview.OverviewDtos.DevelopmentOverviewResponse;
import com.obraviva.platform.overview.OverviewDtos.EconomicOverview;
import com.obraviva.platform.overview.OverviewDtos.HouseCard;
import com.obraviva.platform.overview.OverviewDtos.SetupStep;
import com.obraviva.platform.overview.OverviewDtos.UnitOverviewResponse;
import com.obraviva.platform.progress.ProgressEntryResponse;
import com.obraviva.platform.progress.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Qué tiene un desarrollo o una casa y qué le falta, en una sola lectura.
 *
 * <p>Existe para que el desarrollista entienda la obra de un vistazo: cuánta historia
 * tiene publicada, cuántos recorridos 3D, qué espera revisión, cómo va la venta y el
 * gasto. Junta lo que ya exponen los servicios de cada módulo, llamándolos con el usuario:
 * cada uno aplica su propio control de acceso, así que este servicio no puede mostrar
 * nada que el usuario no podría pedir por separado.
 *
 * <p>Lo económico, lo comercial y los pasos de puesta en marcha son del dueño: para el
 * encargado llegan nulos.
 */
@Service
public class DevelopmentOverviewService {

    private final DevelopmentService developments;
    private final UnitStageService unitStages;
    private final com.obraviva.platform.developments.CaptureSectorService captureSectors;
    private final ProgressService progress;
    private final MediaService media;
    private final Capture3dService captures;
    private final AudioExtractionService audio;
    private final DashboardService dashboard;
    private final DevelopmentTeamService team;
    private final ActivityService activity;

    public DevelopmentOverviewService(
            DevelopmentService developments,
            UnitStageService unitStages,
            com.obraviva.platform.developments.CaptureSectorService captureSectors,
            ProgressService progress,
            MediaService media,
            Capture3dService captures,
            AudioExtractionService audio,
            DashboardService dashboard,
            DevelopmentTeamService team,
            ActivityService activity) {
        this.developments = developments;
        this.unitStages = unitStages;
        this.captureSectors = captureSectors;
        this.progress = progress;
        this.media = media;
        this.captures = captures;
        this.audio = audio;
        this.dashboard = dashboard;
        this.team = team;
        this.activity = activity;
    }

    // --- Desarrollo ---

    public DevelopmentOverviewResponse overview(UUID userId, UUID developmentId) {
        // Primero el detalle: si la obra no es accesible, corta con 404 antes que nada.
        DevelopmentResponse development = developments.detail(userId, developmentId);
        boolean owner = development.role() == OrganizationRole.OWNER;

        List<StageResponse> commonStages = developments.listStages(userId, developmentId);
        List<UnitResponse> units = developments.listUnits(userId, developmentId);
        int unitStageCount = units.stream().map(UnitResponse::stageCount).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();

        ContentOverview content = content(userId, developmentId, commonStages.size() + unitStageCount, null);

        if (!owner) {
            return new DevelopmentOverviewResponse(developmentId, development.kind(), content, null, null, null,
                    houses(userId, developmentId, units, null, null));
        }

        DashboardResponse panel = dashboard.dashboard(userId, developmentId);
        EconomicOverview economic = new EconomicOverview(
                panel.totalBudget(),
                panel.totalSpent(),
                panel.budgetUsedPercent(),
                (int) commonStages.stream().filter(stage -> stage.budgetAmount() != null).count());

        CommercialOverview commercial = new CommercialOverview(
                units.size(),
                count(units, UnitSalesStatus.AVAILABLE),
                count(units, UnitSalesStatus.RESERVED),
                count(units, UnitSalesStatus.SOLD),
                (int) units.stream().filter(unit -> unit.buyerCount() != null && unit.buyerCount() > 0).count(),
                (int) units.stream().filter(DevelopmentOverviewService::hasSheet).count(),
                (int) units.stream().filter(UnitResponse::hasCover).count(),
                (int) units.stream().filter(unit -> unit.stageCount() != null && unit.stageCount() > 0).count(),
                (int) units.stream().filter(unit -> unit.listPrice() != null).count());

        List<TeamPerson> assigned = team.team(userId, developmentId).assigned();
        int supervisors = assigned.size();

        return new DevelopmentOverviewResponse(
                developmentId,
                development.kind(),
                content,
                economic,
                commercial,
                developmentSetup(development, content, commercial, supervisors),
                houses(userId, developmentId, units, panel, assigned));
    }

    /**
     * Las tarjetas de las casas. Cada dato sale del servicio de su módulo llamado con el
     * usuario, así que un encargado no recibe nada de una casa que no podría pedir; el panel
     * y el equipo llegan nulos para él, y con ellos la alerta, el encargado y lo sin ver.
     */
    private List<HouseCard> houses(UUID userId, UUID developmentId, List<UnitResponse> units,
                                   DashboardResponse panel, List<TeamPerson> assigned) {
        boolean owner = panel != null;
        Map<UUID, UnitDashboardRow> rows = owner
                ? panel.units().stream().collect(Collectors.toMap(UnitDashboardRow::unitId, row -> row))
                : Map.of();
        return units.stream().map(unit -> {
            List<StageResponse> unitStageList = unitStages.list(userId, unit.id());
            List<StageProgress> stages = unitStageList.stream()
                    .map(stage -> new StageProgress(stage.id(), stage.name(), stage.orderIndex(), stage.status().name(),
                            stage.progressPercent(), stage.weight(), stage.unitId()))
                    .toList();
            String current = StageCatalogService.currentStage(stages).map(StageProgress::name).orElse(null);
            long running = stages.stream().filter(stage -> isRunning(stage.status())).count();
            boolean started = stages.stream().anyMatch(stage -> !StageStatus.PENDING.name().equals(stage.status()));
            LocalDate plannedStart = started ? null : unitStageList.stream()
                    .min(Comparator.comparingInt(StageResponse::orderIndex))
                    .map(StageResponse::plannedStart).orElse(null);
            List<ActivityItem> last = activity.feed(userId, developmentId, unit.id(), null, null, null, 1).items();
            ActivityItem lastActivity = last.isEmpty() ? null : last.getFirst();
            UnitDashboardRow row = rows.get(unit.id());
            boolean alert = row != null && (row.worstStageLight() == TrafficLight.RED || row.worstStageLight() == TrafficLight.YELLOW);
            return new HouseCard(
                    unit.id(),
                    current,
                    (int) Math.max(0, running - (current != null && running > 0 ? 1 : 0)),
                    plannedStart,
                    lastActivity,
                    owner ? (lastActivity != null && Boolean.TRUE.equals(lastActivity.unseen())) : null,
                    owner ? assigned.stream().anyMatch(person -> person.allHouses() || person.unitIds().contains(unit.id())) : null,
                    alert ? row.worstStageLight() : null,
                    alert ? row.worstStageName() : null);
        }).toList();
    }

    private static boolean isRunning(String status) {
        return StageStatus.IN_PROGRESS.name().equals(status) || StageStatus.DELAYED.name().equals(status);
    }

    /**
     * Puesta en marcha de un desarrollo de casas, en el orden de un desarrollista real:
     * qué es el barrio, qué casas tiene, cómo se construye cada una, quién carga, el
     * recorrido estrella y, al final, a quién se muestra.
     */
    static List<SetupStep> developmentSetup(
            DevelopmentResponse development, ContentOverview content, CommercialOverview commercial, int supervisors) {
        boolean hasUnits = commercial.units() > 0;
        List<SetupStep> steps = new ArrayList<>();
        steps.add(new SetupStep("DETAILS", "Completar los datos del desarrollo",
                "Descripción, ubicación, amenities y portada: lo primero que ve un comprador.",
                present(development.description()) && present(development.locality()) && development.coverUrl() != null));
        steps.add(new SetupStep("UNITS", "Cargar las casas",
                "Cada casa es una obra propia, con sus etapas, su historia y su comprador.",
                hasUnits));
        steps.add(new SetupStep("STAGES", "Cargar las etapas de cada casa",
                "Con la plantilla estándar o a mano. Ordenan la construcción y calculan el avance.",
                hasUnits && commercial.withStages() == commercial.units()));
        steps.add(new SetupStep("SHEETS", "Completar la ficha de cada casa",
                "Superficies, ambientes, descripción y portada, para mostrarla y venderla.",
                hasUnits && commercial.withDetails() == commercial.units() && commercial.withCover() == commercial.units()));
        steps.add(new SetupStep("TEAM", "Asignar al encargado de obra",
                "Es quien publica los avances y graba los recorridos.",
                supervisors > 0));
        steps.add(new SetupStep("FIRST_ENTRY", "Publicar el primer avance",
                "Arranca la historia de obra que sigue el comprador.",
                content.progressEntries() > 0));
        steps.add(new SetupStep("FIRST_CAPTURE", "Grabar el primer recorrido 3D",
                "Un video del lugar que se reconstruye en 3D y se recorre en el tiempo.",
                content.capturesAvailable() > 0));
        steps.add(new SetupStep("BUYER", "Vincular compradores",
                "Siguen la obra de su casa desde Mi propiedad.",
                commercial.unitsWithBuyer() > 0));
        return steps;
    }

    // --- Casa ---

    public UnitOverviewResponse unitOverview(UUID userId, UUID unitId) {
        // Las etapas de la casa verifican el acceso: una casa ajena corta acá con 404.
        List<StageResponse> stages = unitStages.list(userId, unitId);
        UUID developmentId = unitStages.developmentOf(userId, unitId);
        DevelopmentResponse development = developments.detail(userId, developmentId);
        boolean owner = development.role() == OrganizationRole.OWNER;

        UnitResponse unit = developments.listUnits(userId, developmentId).stream()
                .filter(candidate -> candidate.id().equals(unitId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));

        ContentOverview content = content(userId, developmentId, stages.size(), unitId);

        if (!owner) {
            return new UnitOverviewResponse(unitId, developmentId, unit, content, null, null);
        }

        DashboardResponse panel = dashboard.dashboard(userId, developmentId, unitId);
        EconomicOverview economic = new EconomicOverview(
                panel.totalBudget(),
                panel.totalSpent(),
                panel.budgetUsedPercent(),
                (int) stages.stream().filter(stage -> stage.budgetAmount() != null).count());

        int sectors = captureSectors.list(userId, unitId).size();
        return new UnitOverviewResponse(unitId, developmentId, unit, content, economic, unitSetup(unit, content, sectors));
    }

    /** Lo que le falta a una casa para mostrarse, venderse y seguirse. */
    static List<SetupStep> unitSetup(UnitResponse unit, ContentOverview content, int sectors) {
        List<SetupStep> steps = new ArrayList<>();
        steps.add(new SetupStep("SHEET", "Completar la ficha",
                "Superficies, dormitorios, baños y una descripción para el comprador.",
                hasSheet(unit) && present(unit.longDescription())));
        steps.add(new SetupStep("GALLERY", "Subir portada y galería",
                "Las imágenes con las que se muestra la casa.",
                unit.hasCover() && content.galleryPhotos() > 0));
        steps.add(new SetupStep("PRICE", "Cargar el precio de lista",
                "Interno: no lo ve el comprador ni el enlace público.",
                unit.listPrice() != null));
        steps.add(new SetupStep("STAGES", "Cargar las etapas",
                "Con la plantilla estándar de casa o a mano.",
                unit.stageCount() != null && unit.stageCount() > 0));
        steps.add(new SetupStep("SECTORS", "Definir los sectores del recorrido 3D",
                "Qué lugares se graban en cada fecha: el frente, el living, un dormitorio.",
                sectors > 0));
        steps.add(new SetupStep("FIRST_ENTRY", "Publicar el primer avance",
                "Arranca la historia de obra de esta casa.",
                content.progressEntries() > 0));
        steps.add(new SetupStep("FIRST_CAPTURE", "Grabar el primer recorrido 3D",
                "Un video de la casa que se reconstruye en 3D.",
                content.capturesAvailable() > 0));
        steps.add(new SetupStep("BUYER", "Vincular a la familia compradora",
                "Sigue la obra de su casa desde Mi propiedad.",
                unit.buyerCount() != null && unit.buyerCount() > 0));
        return steps;
    }

    // --- Armado ---

    /**
     * Contenido publicado. Con {@code unitId}, sólo lo de esa casa: lo común se resume en
     * el desarrollo. Sin casa, todo el desarrollo.
     */
    private ContentOverview content(UUID userId, UUID developmentId, int stageCount, UUID unitId) {
        List<ProgressEntryResponse> entries = progress.timeline(userId, developmentId).stream()
                .filter(entry -> inScope(unitId, entry.unitId())).toList();
        List<MediaAssetResponse> photos = media.listAvailable(userId, developmentId).stream()
                .filter(asset -> asset.kind() == MediaKind.PHOTO && inScope(unitId, asset.unitId())).toList();
        List<CaptureResponse> available = captures.listAvailable(userId, developmentId).stream()
                .filter(capture -> inScope(unitId, capture.unitId())).toList();
        List<CaptureProcessingResponse> processing = captures.listProcessing(userId, developmentId).stream()
                .filter(capture -> inScope(unitId, capture.unitId())).toList();
        List<ExtractionSummaryResponse> extractions = audio.listByDevelopment(userId, developmentId).stream()
                .filter(extraction -> inScope(unitId, extraction.unitId())).toList();

        return new ContentOverview(
                stageCount,
                entries.size(),
                photos.size(),
                (int) photos.stream().filter(photo -> photo.progressEntryId() == null && photo.unitId() != null).count(),
                available.size(),
                (int) processing.stream().filter(capture -> !"FAILED".equals(capture.jobStatus())).count(),
                (int) processing.stream().filter(capture -> "FAILED".equals(capture.jobStatus())).count(),
                (int) extractions.stream().filter(extraction -> "READY_FOR_REVIEW".equals(extraction.reviewStatus())).count(),
                entries.stream().map(ProgressEntryResponse::entryDate).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null),
                available.stream().map(CaptureResponse::captureDate).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
    }

    private static boolean inScope(UUID scopeUnitId, UUID itemUnitId) {
        return scopeUnitId == null || scopeUnitId.equals(itemUnitId);
    }

    private static boolean hasSheet(UnitResponse unit) {
        return (unit.coveredAreaM2() != null || unit.areaM2() != null) && unit.bedrooms() != null && unit.bathrooms() != null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static int count(List<UnitResponse> units, UnitSalesStatus status) {
        return (int) units.stream().filter(unit -> unit.salesStatus() == status).count();
    }
}
