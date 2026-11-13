package com.obraviva.platform.sharing;

import com.obraviva.platform.captures3d.CaptureCatalogService;
import com.obraviva.platform.comparisons.ComparisonCatalogService;
import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.DevelopmentKind;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.StageCatalogService.StageProgress;
import com.obraviva.platform.developments.UnitCatalogService.UnitSummary;
import com.obraviva.platform.media.MediaCatalogService;
import com.obraviva.platform.media.MediaCatalogService.MediaSummary;
import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.media.variants.ImageVariants;
import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.plans.FloorPlanCatalogService;
import com.obraviva.platform.progress.ProgressService;
import com.obraviva.platform.progress.ProgressService.ProgressSummary;
import com.obraviva.platform.sharing.PublicViewDtos.PublicCapture;
import com.obraviva.platform.sharing.PublicViewDtos.PublicComparison;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFloorPlan;
import com.obraviva.platform.sharing.PublicViewDtos.PublicMedia;
import com.obraviva.platform.sharing.PublicViewDtos.PublicProgressEntry;
import com.obraviva.platform.sharing.PublicViewDtos.PublicRoom;
import com.obraviva.platform.sharing.PublicViewDtos.PublicStage;
import com.obraviva.platform.sharing.PublicViewDtos.PublicTransform;
import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitDetails;
import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitView;
import com.obraviva.platform.storage.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Arma la vista de una unidad. La usan el enlace público y "Mi propiedad".
 *
 * <p>Que las dos compartan proyección es deliberado: hoy el comprador autenticado no
 * ve nada que el enlace no muestre, porque los documentos y la información contractual
 * todavía no existen en el modelo. Mantener dos proyecciones idénticas duplicaría la
 * probabilidad de que una se desincronice y termine exponiendo algo. Cuando aparezcan
 * los documentos, la del comprador se separa y ahí la separación tendrá un motivo.
 *
 * <p>La vigencia de las URLs firmadas la decide quien llama, porque no es la misma
 * para un enlace que circula por mensajería que para una sesión autenticada.
 */
@Component
public class UnitViewAssembler {

    private final StageCatalogService stageCatalog;
    private final ProgressService progressService;
    private final CaptureCatalogService captureCatalog;
    private final MediaCatalogService mediaCatalog;
    private final FloorPlanCatalogService planCatalog;
    private final ComparisonCatalogService comparisonCatalog;
    private final StorageService storageService;
    private final CaptureSectorService sectorService;
    private final ImageVariants imageVariants;
    private final Duration publicAssetTtl;

    public UnitViewAssembler(
            StageCatalogService stageCatalog,
            ProgressService progressService,
            CaptureCatalogService captureCatalog,
            MediaCatalogService mediaCatalog,
            FloorPlanCatalogService planCatalog,
            ComparisonCatalogService comparisonCatalog,
            StorageService storageService,
            CaptureSectorService sectorService,
            ImageVariants imageVariants,
            @Value("${obraviva.sharing.asset-url-ttl:5m}") Duration publicAssetTtl) {
        this.imageVariants = imageVariants;
        this.sectorService = sectorService;
        this.stageCatalog = stageCatalog;
        this.progressService = progressService;
        this.captureCatalog = captureCatalog;
        this.mediaCatalog = mediaCatalog;
        this.planCatalog = planCatalog;
        this.comparisonCatalog = comparisonCatalog;
        this.storageService = storageService;
        this.publicAssetTtl = publicAssetTtl;
    }

    /** Vigencia corta, para lo que sale por un enlace que circula. */
    public Duration publicAssetTtl() {
        return publicAssetTtl;
    }

    @Transactional(readOnly = true)
    public PublicUnitView assemble(UnitSummary unit, Duration assetTtl) {
        List<UUID> organizationIds = List.of(unit.organizationId());

        // Cada casa es su propia obra: sus etapas y su avance. Una unidad sin etapas propias
        // (un departamento de un edificio cargado antes) muestra las obras comunes.
        List<StageProgress> developmentStages = stageCatalog.stageProgressOf(unit.developmentId(), organizationIds);
        List<StageProgress> ownStages = developmentStages.stream()
                .filter(stage -> unit.id().equals(stage.unitId()))
                .toList();
        List<StageProgress> stages = ownStages.isEmpty()
                ? developmentStages.stream().filter(stage -> stage.unitId() == null).toList()
                : ownStages;
        Map<UUID, StageCatalogService.StageDetail> stageDetails = stageCatalog.stageDetailsOf(unit.developmentId(), organizationIds);
        Map<UUID, String> stageNames = new HashMap<>();
        for (StageProgress stage : developmentStages) {
            stageNames.put(stage.id(), stage.name());
        }

        // La historia de la casa y las novedades de las obras comunes; nunca las de otra casa.
        List<ProgressSummary> entries =
                progressService.currentTimeline(unit.developmentId(), organizationIds).stream()
                        .filter(entry -> entry.unitId() == null || entry.unitId().equals(unit.id()))
                        .toList();
        Map<UUID, ProgressSummary> entriesByRoot = new HashMap<>();
        for (ProgressSummary entry : entries) {
            entriesByRoot.put(entry.rootId(), entry);
        }

        // Cada foto se ubica en su avance por la raíz de la versión, no por la versión:
        // si el avance se corrigió después de subirla, la vigente es otra.
        Map<UUID, UUID> rootByEntry =
                progressService.rootIdsByEntryId(unit.developmentId(), organizationIds);
        List<PublicMedia> gallery = new ArrayList<>();
        Map<UUID, List<PublicMedia>> photosByRoot = new HashMap<>();
        Map<UUID, PublicMedia> photosById = new HashMap<>();
        List<PublicMedia> showcase = new ArrayList<>();
        List<PlacesProjection.PlaceMedia> placeMedia = new ArrayList<>();
        for (MediaSummary asset : mediaCatalog.availableMedia(unit.developmentId(), organizationIds)) {
            if (asset.unitId() != null && !asset.unitId().equals(unit.id())) {
                continue;
            }
            ProgressSummary entry = asset.progressEntryId() == null
                    ? null
                    : entriesByRoot.get(rootByEntry.get(asset.progressEntryId()));
            PublicMedia media = new PublicMedia(
                    storageService.presignDownload(asset.storageKey(), assetTtl),
                    asset.contentType(),
                    asset.kind().name(),
                    asset.createdAt(),
                    entry == null ? null : entry.entryDate(),
                    entry == null ? null : stageNames.get(entry.stageId()),
                    asset.takenOn(),
                    asset.thumbStorageKey() == null ? null : storageService.presignDownload(asset.thumbStorageKey(), assetTtl),
                    asset.screenStorageKey() == null ? null : storageService.presignDownload(asset.screenStorageKey(), assetTtl));
            gallery.add(media);
            if (asset.kind() == MediaKind.PHOTO) {
                photosById.put(asset.id(), media);
            }
            // Galería de la casa: fotos cargadas para mostrarla, no evidencia de un avance.
            if (asset.kind() == MediaKind.PHOTO && asset.showcase() && unit.id().equals(asset.unitId())) {
                showcase.add(media);
            }
            if (entry != null && asset.kind() == MediaKind.PHOTO) {
                photosByRoot.computeIfAbsent(entry.rootId(), root -> new ArrayList<>()).add(media);
            }
            // Lugar por lugar va sólo lo de la obra de la casa: lo de las obras comunes no tiene
            // sector, y la galería es material de venta (renders, el modelo terminado), no la obra.
            // Una foto de un avance que ya no está vigente no se muestra en ningún lado.
            if (unit.id().equals(asset.unitId()) && !asset.showcase() && (asset.progressEntryId() == null || entry != null)) {
                placeMedia.add(new PlacesProjection.PlaceMedia(
                        asset.sector(), entry == null ? null : entry.entryDate(), asset.takenOn(), asset.createdAt(),
                        asset.kind() == MediaKind.VIDEO, media,
                        entry == null ? null : new PlacesProjection.PlaceNote(
                                entry.rootId(), entry.entryDate(), stageNames.get(entry.stageId()), entry.description()),
                        asset.showcase()));
            }
        }

        Map<UUID, ProgressService.RecordedStageChange> changes = progressService.stageChangesByRoot(unit.developmentId(), organizationIds);
        List<PublicProgressEntry> timeline = entries.stream()
                .map(entry -> new PublicProgressEntry(
                        entry.entryDate(),
                        entry.stageId() == null ? null : stageNames.get(entry.stageId()),
                        entry.description(),
                        entry.source(),
                        entry.corrected(),
                        // El catálogo trae los medios del más nuevo al más viejo; dentro de
                        // un avance se muestran en el orden en que se subieron.
                        photosByRoot.getOrDefault(entry.rootId(), List.of()).reversed(),
                        entry.unitId() == null,
                        stageChangeOf(changes.get(entry.rootId()))))
                .toList();

        // En un edificio cada unidad ve sus recorridos y los de áreas comunes, nunca los de
        // otra unidad. En una obra única todo recorrido es de la casa, indique unidad o no.
        boolean single = unit.developmentKind() == DevelopmentKind.SINGLE;
        List<PublicCapture> captures = captureCatalog
                .renderableCaptures(unit.developmentId(), organizationIds)
                .stream()
                .filter(capture -> single || capture.unitId() == null || capture.unitId().equals(unit.id()))
                .map(capture -> new PublicCapture(
                        capture.captureDate(),
                        capture.description(),
                        capture.zone(),
                        capture.stageId() == null ? null : stageNames.get(capture.stageId()),
                        !single && capture.unitId() == null,
                        storageService.presignDownload(capture.storageKey(), assetTtl),
                        new PublicTransform(
                                capture.positionX(), capture.positionY(), capture.positionZ(),
                                capture.rotationX(), capture.rotationY(), capture.rotationZ(),
                                capture.scale())))
                .toList();

        // El comparador sigue la misma regla de alcance que los recorridos, y sólo publica
        // comparaciones cuya foto sigue disponible.
        List<ComparisonCatalogService.CatalogComparison> visibleComparisons = comparisonCatalog
                .completeComparisons(unit.developmentId(), unit.organizationId())
                .stream()
                .filter(comparison -> single || comparison.unitId() == null || comparison.unitId().equals(unit.id()))
                .filter(comparison -> photosById.containsKey(comparison.photoMediaId()))
                .toList();
        // "Tu casa, de frente": el render del frente que cargó el dueño y, si no hay, la
        // comparación más reciente de la casa (render contra su propia foto). El catálogo viene
        // de la más vieja a la más nueva; las de toda la obra no son "de la casa".
        String frontSector = sectorService.frontOf(unit.id()).orElse(null);
        // Las portadas y los renders con sus versiones livianas, en una sola consulta.
        ImageVariants.Images images = imageVariants.lookup(List.of(unit.organizationId()), Stream.concat(
                Stream.of(unit.coverStorageKey(), unit.frontRenderStorageKey(), unit.development().coverStorageKey()),
                visibleComparisons.stream().map(ComparisonCatalogService.CatalogComparison::renderStorageKey)).toList());
        SignedImage frontRender = images.sign(unit.frontRenderStorageKey(), assetTtl);
        PublicViewDtos.PublicFrontProject frontFallback = visibleComparisons.stream()
                .filter(comparison -> unit.id().equals(comparison.unitId()) || (single && comparison.unitId() == null))
                .reduce((older, newer) -> newer)
                .map(comparison -> {
                    PublicMedia photo = photosById.get(comparison.photoMediaId());
                    SignedImage render = images.sign(comparison.renderStorageKey(), assetTtl);
                    return new PublicViewDtos.PublicFrontProject(
                            render.url(),
                            render.screenUrl(),
                            photo.url(),
                            photo.screenUrl(),
                            PlacesProjection.dayOf(photo));
                })
                .orElse(null);
        List<PublicComparison> comparisons = visibleComparisons
                .stream()
                .map(comparison -> {
                    PublicMedia photo = photosById.get(comparison.photoMediaId());
                    SignedImage render = images.sign(comparison.renderStorageKey(), assetTtl);
                    return new PublicComparison(
                            comparison.title(),
                            render.url(),
                            render.thumbUrl(),
                            render.screenUrl(),
                            photo.url(),
                            photo.entryDate(),
                            photo.stageName(),
                            !single && comparison.unitId() == null);
                })
                .toList();

        return new PublicUnitView(
                unit.code(),
                unit.description(),
                unit.developmentName(),
                unit.developmentAddress(),
                unit.developmentKind().name(),
                new PublicUnitDetails(
                        unit.areaM2(),
                        unit.bedrooms(),
                        unit.bathrooms(),
                        unit.features(),
                        unit.estimatedDelivery(),
                        urlOf(images.sign(unit.coverStorageKey(), assetTtl)),
                        thumbOf(images.sign(unit.coverStorageKey(), assetTtl)),
                        screenOf(images.sign(unit.coverStorageKey(), assetTtl)),
                        unit.sheet().modelName(),
                        unit.sheet().lotAreaM2(),
                        unit.sheet().coveredAreaM2(),
                        unit.sheet().semiCoveredAreaM2(),
                        unit.sheet().rooms(),
                        unit.sheet().toilettes(),
                        unit.sheet().floors(),
                        unit.sheet().parkingSpaces(),
                        unit.sheet().orientation(),
                        unit.sheet().longDescription(),
                        unit.sheet().finishes()),
                StageCatalogService.overallProgressPercent(stages),
                StageCatalogService.currentStage(stages).map(stage -> toPublicStage(stage, stageDetails)).orElse(null),
                stages.stream().map(stage -> toPublicStage(stage, stageDetails)).toList(),
                timeline,
                captures,
                gallery,
                planCatalog.plansOf(unit.id(), unit.organizationId()).stream()
                        .map(entry -> new PublicFloorPlan(
                                entry.plan().name(),
                                entry.plan().widthM(),
                                entry.plan().depthM(),
                                entry.plan().rooms().stream()
                                        .map(room -> new PublicRoom(
                                                room.name(), room.polygon(), room.areaM2(), room.declaredArea()))
                                        .toList(),
                                entry.imageStorageKey() == null
                                        ? null
                                        : storageService.presignDownload(entry.imageStorageKey(), assetTtl)))
                        .toList(),
                comparisons,
                showcase,
                new PublicViewDtos.PublicDevelopment(
                        unit.development().description(),
                        unit.development().locality(),
                        unit.development().province(),
                        unit.development().developmentType(),
                        unit.development().landAreaM2(),
                        unit.development().amenities(),
                        unit.development().services(),
                        unit.development().architect(),
                        unit.development().builder(),
                        unit.development().estimatedDelivery(),
                        urlOf(images.sign(unit.development().coverStorageKey(), assetTtl)),
                        thumbOf(images.sign(unit.development().coverStorageKey(), assetTtl)),
                        screenOf(images.sign(unit.development().coverStorageKey(), assetTtl))),
                unit.sectors(),
                PlacesProjection.places(
                        unit.sectors(),
                        placeMedia,
                        captures.stream()
                                .filter(capture -> !capture.common())
                                .map(capture -> new PlacesProjection.PlaceTour(capture.zone(), capture.date()))
                                .toList()),
                PlacesProjection.front(frontSector, placeMedia, frontRender, frontFallback));
    }

    private static String urlOf(SignedImage image) {
        return image == null ? null : image.url();
    }

    private static String thumbOf(SignedImage image) {
        return image == null ? null : image.thumbUrl();
    }

    private static String screenOf(SignedImage image) {
        return image == null ? null : image.screenUrl();
    }

    private static PublicStage toPublicStage(StageProgress stage, Map<UUID, StageCatalogService.StageDetail> details) {
        StageCatalogService.StageDetail detail = details.get(stage.id());
        return new PublicStage(stage.name(), stage.orderIndex(), stage.status(), stage.progressPercent(),
                detail == null ? null : detail.description(),
                detail == null ? null : detail.plannedStart(),
                detail == null ? null : detail.plannedEnd(),
                detail == null ? null : detail.startedOn(),
                detail == null ? null : detail.completedOn());
    }

    private static PublicViewDtos.PublicStageChange stageChangeOf(ProgressService.RecordedStageChange change) {
        return change == null ? null : new PublicViewDtos.PublicStageChange(
                change.percentBefore(), change.percentAfter(), change.statusBefore(), change.statusAfter());
    }
}
