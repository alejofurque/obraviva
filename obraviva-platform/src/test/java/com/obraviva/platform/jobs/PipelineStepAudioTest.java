package com.obraviva.platform.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica que los dos pipelines conviven en el mismo enum sin mezclarse.
 */
class PipelineStepAudioTest {

    private ProcessingJob audioJob() {
        return new ProcessingJob(
                UUID.randomUUID(), JobType.AUDIO_EXTRACTION, UUID.randomUUID());
    }

    private ProcessingJob captureJob() {
        return new ProcessingJob(
                UUID.randomUUID(), JobType.CAPTURE_3D_RECONSTRUCTION, UUID.randomUUID());
    }

    @Test
    @DisplayName("cada tipo de trabajo nace en el primer paso de su propio pipeline")
    void eachJobTypeStartsAtItsOwnFirstStep() {
        assertThat(audioJob().getPipelineStep()).isEqualTo(PipelineStep.TRANSCRIBE);
        assertThat(captureJob().getPipelineStep()).isEqualTo(PipelineStep.PREPROCESS);
    }

    @Test
    @DisplayName("el pipeline de audio avanza transcribir, interpretar, revisar, listo")
    void audioPipelineAdvancesInOrder() {
        ProcessingJob job = audioJob();
        job.transitionTo(JobStatus.PENDING);

        job.markProcessing();
        job.completeStep(PipelineStep.INTERPRET);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.INTERPRET);

        job.markProcessing();
        job.completeStep(PipelineStep.AWAIT_REVIEW);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_REVIEW);

        job.markProcessing();
        job.finishPipeline();
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.DONE);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("los pasos de un pipeline no son alcanzables desde el otro")
    void pipelinesDoNotCross() {
        // Sin esta separación, un error de programación podría enviar un trabajo de
        // audio al worker de reconstrucción y viceversa.
        ProcessingJob job = audioJob();
        job.transitionTo(JobStatus.PENDING);
        job.markProcessing();

        assertThatThrownBy(() -> job.completeStep(PipelineStep.SUBMIT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PipelineStep.CONVERT.canAdvanceTo(PipelineStep.TRANSCRIBE)).isFalse();
        assertThat(PipelineStep.TRANSCRIBE.canAdvanceTo(PipelineStep.SUBMIT)).isFalse();
    }

    @Test
    @DisplayName("ambos pipelines terminan en el mismo paso final")
    void bothPipelinesShareTheFinalStep() {
        assertThat(PipelineStep.CONVERT.canAdvanceTo(PipelineStep.DONE)).isTrue();
        assertThat(PipelineStep.AWAIT_REVIEW.canAdvanceTo(PipelineStep.DONE)).isTrue();
        assertThat(PipelineStep.DONE.canAdvanceTo(PipelineStep.DONE)).isFalse();
    }
}