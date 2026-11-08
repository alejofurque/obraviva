package com.obraviva.platform.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineStepTest {

    private ProcessingJob newJob() {
        return new ProcessingJob(
                UUID.randomUUID(), JobType.CAPTURE_3D_RECONSTRUCTION, UUID.randomUUID());
    }

    private ProcessingJob claimedJob() {
        ProcessingJob job = newJob();
        job.transitionTo(JobStatus.PENDING);
        job.markProcessing();
        return job;
    }

    @Test
    @DisplayName("todo trabajo nace en el paso de preprocesamiento")
    void jobStartsAtPreprocess() {
        assertThat(newJob().getPipelineStep()).isEqualTo(PipelineStep.PREPROCESS);
    }

    @Test
    @DisplayName("completar un paso devuelve el trabajo a la cola en el siguiente")
    void completingAStepRequeuesAtNextStep() {
        ProcessingJob job = claimedJob();
        assertThat(job.getAttempts()).isEqualTo(1);

        job.completeStep(PipelineStep.SUBMIT);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
    }

    @Test
    @DisplayName("completar un paso reinicia los intentos del paso siguiente")
    void completingAStepResetsAttempts() {
        // Sin este reinicio, un trabajo que atraviesa varios pasos exitosos agotaría
        // los reintentos permitidos sin haber fallado nunca.
        ProcessingJob job = claimedJob();
        job.completeStep(PipelineStep.SUBMIT);

        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();
    }

    @Test
    @DisplayName("no se puede saltear un paso")
    void stepsCannotBeSkipped() {
        ProcessingJob job = claimedJob();

        assertThatThrownBy(() -> job.completeStep(PipelineStep.DOWNLOAD))
                .isInstanceOf(IllegalStateException.class);

        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.PREPROCESS);
    }

    @Test
    @DisplayName("completar un paso puede registrar la referencia del servicio externo")
    void completingAStepCanRecordExternalReference() {
        ProcessingJob job = claimedJob();
        job.completeStep(PipelineStep.SUBMIT);
        job.markProcessing();

        job.completeStep(PipelineStep.AWAIT_EXTERNAL, "kiri-abc123");

        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
        assertThat(job.getExternalReference()).isEqualTo("kiri-abc123");
        assertThat(job.getAttempts()).isZero();
    }

    @Test
    @DisplayName("volver a esperar no avanza el paso ni mueve su marca de inicio")
    void awaitingAgainKeepsStepAndItsStartMark() {
        ProcessingJob job = claimedJob();
        job.completeStep(PipelineStep.SUBMIT);
        Instant stepStart = job.getStepStartedAt();

        job.markProcessing();
        job.awaitAgain();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
        // La marca no se mueve: es lo que permite medir cuánto llevamos esperando
        // al tercero aunque lo consultemos muchas veces.
        assertThat(job.getStepStartedAt()).isEqualTo(stepStart);
    }

    @Test
    @DisplayName("tomar el trabajo no borra la referencia externa ya asignada")
    void claimingDoesNotEraseExistingExternalReference() {
        ProcessingJob job = claimedJob();
        job.completeStep(PipelineStep.SUBMIT);
        job.markProcessing("kiri-123");
        job.completeStep(PipelineStep.AWAIT_EXTERNAL);

        job.markProcessing();

        assertThat(job.getExternalReference()).isEqualTo("kiri-123");
    }
}