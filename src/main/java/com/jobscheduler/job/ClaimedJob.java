package com.jobscheduler.job;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable snapshot of a job at the moment it was claimed, handed from the poller to a
 * worker thread (entities are not shared across threads).
 *
 * <p>{@code workerId} + {@code attempt} identify this particular claim. Every later update for
 * the job includes them in its WHERE clause, so a worker whose claim has been taken away (lease
 * expired and the job was re-claimed) can no longer change the job's state.
 */
public record ClaimedJob(
        UUID id,
        String type,
        JsonNode payload,
        int priority,
        int attempt,
        int maxAttempts,
        Instant runAt,
        Instant claimedAt,
        String workerId
) {

    static ClaimedJob from(Job job) {
        return new ClaimedJob(job.getId(), job.getType(), job.getPayload(), job.getPriority(),
                job.getAttempts(), job.getMaxAttempts(), job.getRunAt(), job.getLockedAt(), job.getLockedBy());
    }
}
