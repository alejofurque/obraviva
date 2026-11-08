package com.obraviva.platform.jobs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTest {

    private ProcessingJob newJob() {
        return new ProcessingJob(
                UUID.randomUUID(), JobType.CAPTURE_3D_RECONSTRUCTION, UUID.randomUUID());
    }

    @Test
    void jobStartsUploaded() {
        assertThat(newJob().getStatus()).isEqualTo(JobStatus.UPLOADED);
    }

    @Test
    void followsHappyPath() {
        ProcessingJob job = newJob();
        job.transitionTo(JobStatus.PENDING);
        job.markProcessing("kiri-123");
        job.transitionTo(JobStatus.COMPLETED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getExternalReference()).isEqualTo("kiri-123");
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    void rejectsSkippingStates() {
        ProcessingJob job = newJob();

        assertThatThrownBy(() -> job.transitionTo(JobStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesAreFinal() {
        ProcessingJob job = newJob();
        job.transitionTo(JobStatus.PENDING);
        job.markFailed("timeout del servicio externo");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThatThrownBy(() -> job.transitionTo(JobStatus.PENDING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requeueAllowsRetryAfterTransientFailure() {
        ProcessingJob job = newJob();
        job.transitionTo(JobStatus.PENDING);
        job.markProcessing("kiri-1");
        job.requeue("error de red");
        job.markProcessing("kiri-2");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getAttempts()).isEqualTo(2);
    }
}