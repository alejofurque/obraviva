package com.obraviva.platform.jobs;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * API pública del módulo de trabajos. Ningún otro módulo accede al repositorio ni
 * muta entidades de trabajos directamente.
 */
@Service
public class ProcessingJobService {

    private final ProcessingJobRepository jobRepository;

    public ProcessingJobService(ProcessingJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public ProcessingJob create(UUID organizationId, JobType jobType, UUID resourceId) {
        return jobRepository.save(new ProcessingJob(organizationId, jobType, resourceId));
    }

    /** El archivo llegó al almacenamiento: el trabajo queda listo para procesarse. */
    @Transactional
    public void markReady(UUID resourceId) {
        jobRepository.findByResourceId(resourceId)
                .ifPresent(job -> job.transitionTo(JobStatus.PENDING));
    }

    /**
     * Toma el trabajo pendiente más antiguo del tipo indicado, siempre que su paso
     * actual esté entre los que el llamador sabe ejecutar. Un trabajo posicionado en
     * un paso no soportado queda quieto en la cola en vez de ser reclamado una y otra
     * vez para no hacer nada.
     *
     * <p>No hay bloqueo pesimista: el planificador corre con un único hilo y cada
     * worker procesa un trabajo por ciclo, así que no existe competencia. Con varios
     * consumidores haría falta un SELECT ... FOR UPDATE SKIP LOCKED.
     */
    @Transactional
    public Optional<ProcessingJob> claimNext(JobType jobType, Set<PipelineStep> supportedSteps) {
        if (supportedSteps == null || supportedSteps.isEmpty()) {
            return Optional.empty();
        }
        return jobRepository
                .findFirstByStatusAndJobTypeAndPipelineStepInOrderByCreatedAtAsc(
                        JobStatus.PENDING, jobType, supportedSteps)
                .map(job -> {
                    job.markProcessing();
                    return job;
                });
    }

    /** El paso terminó bien: el trabajo vuelve a la cola en el paso siguiente. */
    @Transactional
    public void completeStep(UUID jobId, PipelineStep next) {
        completeStep(jobId, next, null);
    }

    /**
     * El paso terminó bien y delegó trabajo en un servicio externo: se avanza de paso
     * y se registra la referencia devuelta en una sola operación.
     */
    @Transactional
    public void completeStep(UUID jobId, PipelineStep next, String externalReference) {
        jobRepository.findById(jobId).ifPresent(job -> job.completeStep(next, externalReference));
    }

    /**
     * El último paso terminó: el trabajo avanza a DONE y queda cerrado en una sola
     * operación, mientras todavía está tomado. Ver {@link ProcessingJob#finishPipeline()}.
     */
    @Transactional
    public void finishPipeline(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(ProcessingJob::finishPipeline);
    }

        /** La interpretación terminó: el trabajo espera confirmación humana. */
    @Transactional
    public void awaitHumanReview(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(ProcessingJob::awaitHumanReview);
    }

    /** El tercero sigue trabajando: se reencola sin avanzar y sin contar como fallo. */
    @Transactional
    public void awaitAgain(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(ProcessingJob::awaitAgain);
    }

    /** Fallo definitivo: el material o los datos no sirven y reintentar no cambia nada. */
    @Transactional
    public void fail(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> job.markFailed(error));
    }

    /**
     * Fallo técnico: devuelve el trabajo a la cola, salvo que ya se hayan agotado los
     * intentos permitidos del paso actual.
     */
    @Transactional
    public void retryOrFail(UUID jobId, String error, int maxAttempts) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getAttempts() >= maxAttempts) {
                job.markFailed("Se agotaron los reintentos. Último error: " + error);
            } else {
                job.requeue(error);
            }
        });
    }

    /** Los trabajos de un tipo que quedaron tomados, del más viejo al más nuevo. */
    @Transactional(readOnly = true)
    public List<ProcessingJob> findInProcess(JobType jobType) {
        return jobRepository.findByStatusAndJobTypeOrderByCreatedAtAsc(JobStatus.PROCESSING, jobType);
    }

    @Transactional(readOnly = true)
    public Optional<ProcessingJob> findByResource(UUID resourceId) {
        return jobRepository.findByResourceId(resourceId);
    }

    /**
     * Trabajos de un conjunto de recursos, indexados por recurso. Existe para que un
     * listado no haga una consulta por fila.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ProcessingJob> findByResources(
            Collection<UUID> organizationIds, Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return Map.of();
        }
        return jobRepository.findByOrganizationIdInAndResourceIdIn(organizationIds, resourceIds)
                .stream()
                .collect(Collectors.toMap(ProcessingJob::getResourceId, Function.identity()));
    }

    /** Borra el trabajo de un recurso descartado. Quien llama verifica que se pueda. */
    @Transactional
    public void deleteByResource(UUID resourceId) {
        jobRepository.findByResourceId(resourceId).ifPresent(jobRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<ProcessingJob> findPending() {
        return jobRepository.findByStatusOrderByCreatedAtAsc(JobStatus.PENDING);
    }
}