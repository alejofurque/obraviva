package com.obraviva.platform.reports;

import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
import com.obraviva.platform.reports.narration.NarrativeGuard;
import com.obraviva.platform.reports.narration.ReportNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Ejecuta el paso de redacción del reporte de período.
 *
 * <p>Corre en tres tiempos, igual que los demás workers: transacción corta para tomar
 * el trabajo, llamada al proveedor sin transacción abierta, transacción corta para
 * registrar el desenlace.
 *
 * <p>Entre la redacción y el registro se interpone la salvaguarda de cifras: un texto
 * con números ajenos al snapshot no llega al revisor, se reintenta. La comprobación
 * se aplica a todo adaptador, incluido el simulado.
 *
 * <p>Al terminar, el trabajo queda en {@code NEEDS_REVIEW} y sale de la cola: lo
 * siguiente lo decide el desarrollista, no un worker.
 */
@Component
public class ReportWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportWorker.class);

    private final ReportService reportService;
    private final ReportNarrator narrator;
    private final NarrativeGuard guard;
    private final boolean enabled;

    public ReportWorker(
            ReportService reportService,
            ReportNarrator narrator,
            NarrativeGuard guard,
            @Value("${obraviva.jobs.enabled:true}") boolean enabled) {
        this.reportService = reportService;
        this.narrator = narrator;
        this.guard = guard;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${obraviva.jobs.poll-interval-ms:10000}",
            initialDelayString = "${obraviva.jobs.report-initial-delay-ms:35000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            processNext();
        } catch (RuntimeException e) {
            // El planificador cancela la tarea si una ejecución propaga una excepción.
            log.error("Error inesperado en el ciclo de redacción de reportes", e);
        }
    }

    /** Procesa un trabajo si hay alguno pendiente. Expuesto para poder ejercitarlo en tests. */
    public void processNext() {
        Optional<ReportNarrationTask> task = reportService.claimNext();
        task.ifPresent(this::narrate);
    }

    private void narrate(ReportNarrationTask task) {
        log.info("Redactando el reporte {} ({} avances, {} capturas)",
                task.reportId(),
                task.data().totals().progressEntries(),
                task.data().totals().captures());

        try {
            Narration narration = narrator.narrate(task.data());

            // Ninguna cifra sale de un modelo de lenguaje: acá se comprueba, no se
            // confía.
            guard.verify(narration.text(), task.data());

            reportService.recordNarration(
                    task.jobId(),
                    task.reportId(),
                    narration.text(),
                    narration.rawOutput(),
                    narration.promptVersion(),
                    narration.modelId());

            log.info("Reporte {} redactado ({} caracteres) por {} (prompt {}). Espera aprobación.",
                    task.reportId(), narration.text().length(),
                    narration.modelId(), narration.promptVersion());

        } catch (NarrationException e) {
            if (e.hasRawOutput()) {
                reportService.recordFailedOutput(
                        task.jobId(), task.reportId(), e.rawOutput(), e.getMessage());
            } else {
                reportService.recordTechnicalFailure(
                        task.jobId(), task.reportId(), e.getMessage());
            }
            log.error("Fallo redactando el reporte {}: {}", task.reportId(), e.getMessage());

        } catch (RuntimeException e) {
            log.error("Fallo técnico redactando el reporte {}", task.reportId(), e);
            reportService.recordTechnicalFailure(
                    task.jobId(), task.reportId(), String.valueOf(e.getMessage()));
        }
    }
}