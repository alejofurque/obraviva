package com.obraviva.platform.jobs;

import java.util.Set;

/**
 * Estados de un trabajo asincrónico. Las transiciones válidas se
 * declaran acá: un trabajo no puede saltar de UPLOADED a COMPLETED.
 */
public enum JobStatus {

    UPLOADED,
    PENDING,
    PROCESSING,
    NEEDS_REVIEW,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(JobStatus target) {
        return switch (this) {
            case UPLOADED -> Set.of(PENDING, FAILED).contains(target);
            case PENDING -> Set.of(PROCESSING, FAILED).contains(target);
            case PROCESSING -> Set.of(COMPLETED, NEEDS_REVIEW, FAILED, PENDING).contains(target);
            case NEEDS_REVIEW -> Set.of(COMPLETED, FAILED).contains(target);
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}