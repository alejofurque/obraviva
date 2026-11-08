package com.obraviva.platform.captures3d;

import com.obraviva.platform.jobs.PipelineStep;

/**
 * Trabajo tomado de la cola, con el paso que le corresponde ejecutar.
 *
 * <p>Existe porque el worker de reconstrucción atiende varios pasos y necesita saber
 * cuál le tocó sin volver a consultar la base fuera de la transacción. El campo
 * {@code task} lleva el record específico del paso.
 */
public record ClaimedStep(PipelineStep step, Object task) {

    public ReconstructionSubmissionTask asSubmission() {
        return (ReconstructionSubmissionTask) task;
    }

    public ReconstructionPollTask asPoll() {
        return (ReconstructionPollTask) task;
    }

    public ReconstructionDownloadTask asDownload() {
        return (ReconstructionDownloadTask) task;
    }
}