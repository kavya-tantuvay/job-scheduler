package com.jobscheduler.job.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.job.Job;
import com.jobscheduler.job.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        JsonNode payload,
        JobStatus status,
        int priority,
        int attempts,
        int maxAttempts,
        Instant runAt,
        Instant lockedAt,
        String lockedBy,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getType(),
                job.getPayload(),
                job.getStatus(),
                job.getPriority(),
                job.getAttempts(),
                job.getMaxAttempts(),
                job.getRunAt(),
                job.getLockedAt(),
                job.getLockedBy(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
