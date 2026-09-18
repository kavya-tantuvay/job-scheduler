package com.jobscheduler.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a job.
 *
 * <pre>
 *   PENDING ──claim──► RUNNING ──ok──────────► SUCCEEDED
 *      │   ◄──retry /    │  ├──non-retryable─► FAILED
 *      │     lease expiry┘  └──retries spent─► DEAD   (dead-letter)
 *      └──cancel──► CANCELLED
 * </pre>
 */
public enum JobStatus {

    /** Waiting in the queue; eligible to be claimed once {@code run_at <= now}. */
    PENDING,
    /** Claimed by a worker and currently executing. */
    RUNNING,
    SUCCEEDED,
    /** Failed with an error that retrying cannot fix (e.g. no handler, invalid payload). */
    FAILED,
    /** Dead-letter: every allowed attempt failed. */
    DEAD,
    CANCELLED;

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    public boolean canTransitionTo(JobStatus target) {
        return allowedTransitions().contains(target);
    }

    private Set<JobStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, DEAD, PENDING);
            case SUCCEEDED, FAILED, DEAD, CANCELLED -> EnumSet.noneOf(JobStatus.class);
        };
    }
}
