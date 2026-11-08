package com.obraviva.platform.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pipeline de generación de reportes. La composición del snapshot no aparece acá a
 * propósito: ocurre de forma sincrónica antes de encolar, y lo único que la cola
 * ejecuta es la redacción.
 */
class PipelineStepReportTest {

    private ProcessingJob claimedReportJob() {
        ProcessingJob job = new ProcessingJob(
                UUID.randomUUID(), JobType.REPORT_GENERATION, UUID.randomUUID());
        job.transitionTo(JobStatus.PENDING);
        job.markProcessing();
        return job;
    }

    @Test
    @DisplayName("un trabajo de reporte nace en el paso de redacción")
    void reportJobStartsAtNarrate() {
        ProcessingJob job = new ProcessingJob(
                UUID.randomUUID(), JobType.REPORT_GENERATION, UUID.randomUUID());

        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.NARRATE);
    }

    @Test
    @DisplayName("redactar deja el trabajo esperando a una persona, fuera de la cola")
    void narrationLeavesJobAwaitingReview() {
        ProcessingJob job = claimedReportJob();

        job.awaitHumanReview();

        assertThat(job.getStatus()).isEqualTo(JobStatus.NEEDS_REVIEW);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_REVIEW);
    }

    @Test
    @DisplayName("el reporte no puede darse por terminado sin pasar por la revisión")
    void reportCannotSkipReview() {
        ProcessingJob job = claimedReportJob();

        assertThatThrownBy(job::finishPipeline).isInstanceOf(IllegalStateException.class);

        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.NARRATE);
    }
}