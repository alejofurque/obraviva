package com.obraviva.platform.reports;

import com.obraviva.platform.captures3d.CaptureCatalogService;
import com.obraviva.platform.developments.DevelopmentAccessService.DevelopmentSummary;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.progress.ProgressService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Motor de datos del reporte: arma el snapshot del período consultando a los módulos
 * de dominio a través de sus APIs públicas.
 *
 * <p>Todo lo que después aparecerá como cifra en el reporte se calcula acá. El modelo
 * de lenguaje no suma, no cuenta y no infiere: recibe este objeto ya resuelto y
 * escribe prosa sobre él.
 */
@Component
public class ReportComposer {

    private final StageCatalogService stageCatalog;
    private final ProgressService progressService;
    private final CaptureCatalogService captureCatalog;

    public ReportComposer(
            StageCatalogService stageCatalog,
            ProgressService progressService,
            CaptureCatalogService captureCatalog) {
        this.stageCatalog = stageCatalog;
        this.progressService = progressService;
        this.captureCatalog = captureCatalog;
    }

    public ReportData compose(
            DevelopmentSummary development,
            List<UUID> organizationIds,
            LocalDate from,
            LocalDate to) {

        List<StageCatalogService.StageSummary> stages =
                stageCatalog.stagesOf(development.id(), organizationIds);

        Map<UUID, String> stageNames = new HashMap<>();
        for (StageCatalogService.StageSummary stage : stages) {
            stageNames.put(stage.id(), stage.name());
        }

        List<ReportData.StageState> stageStates = stages.stream()
                .map(stage -> new ReportData.StageState(
                        stage.id().toString(), stage.name(), stage.orderIndex(), stage.status()))
                .toList();

        List<ReportData.ProgressItem> entries = progressService
                .currentEntriesInPeriod(development.id(), organizationIds, from, to)
                .stream()
                .map(entry -> new ReportData.ProgressItem(
                        entry.entryDate().toString(),
                        // Una etapa borrada o de otro desarrollo no debe inventarse un
                        // nombre: el avance se narra sin etapa, que es un caso legítimo
                        // del modelo de datos.
                        entry.stageId() == null ? null : stageNames.get(entry.stageId()),
                        entry.description(),
                        entry.source()))
                .toList();

        List<ReportData.CaptureItem> captures = captureCatalog
                .availableCapturesInPeriod(development.id(), organizationIds, from, to)
                .stream()
                .map(capture -> new ReportData.CaptureItem(
                        capture.id().toString(),
                        capture.captureDate().toString(),
                        capture.description()))
                .toList();

        ReportData.Totals totals = new ReportData.Totals(
                entries.size(),
                captures.size(),
                countByStatus(stageStates, "COMPLETED"),
                countByStatus(stageStates, "IN_PROGRESS"),
                countByStatus(stageStates, "DELAYED"),
                countByStatus(stageStates, "PENDING"));

        return new ReportData(
                development.name(),
                development.address(),
                from.toString(),
                to.toString(),
                stageStates,
                entries,
                captures,
                totals);
    }

    private static int countByStatus(List<ReportData.StageState> stages, String status) {
        return (int) stages.stream()
                .filter(stage -> status.equals(stage.statusAtCompositionTime()))
                .count();
    }
}