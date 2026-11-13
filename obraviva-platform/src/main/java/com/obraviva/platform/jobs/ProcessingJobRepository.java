package com.obraviva.platform.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    Optional<ProcessingJob> findByResourceId(UUID resourceId);

    List<ProcessingJob> findByStatusAndJobTypeOrderByCreatedAtAsc(JobStatus status, JobType jobType);

    /** Trabajos de varios recursos en una sola consulta, acotados por organización. */
    List<ProcessingJob> findByOrganizationIdInAndResourceIdIn(
            Collection<UUID> organizationIds, Collection<UUID> resourceIds);

    /**
     * Trabajo pendiente más antiguo de un tipo, restringido a los pasos que el worker
     * sabe ejecutar. La cola es FIFO; los pasos no soportados quedan sin reclamar.
     */
    Optional<ProcessingJob> findFirstByStatusAndJobTypeAndPipelineStepInOrderByCreatedAtAsc(
            JobStatus status, JobType jobType, Collection<PipelineStep> pipelineSteps);
}