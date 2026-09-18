package com.jobscheduler.schedule.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.schedule.RecurringJob;

import java.time.Instant;
import java.util.UUID;

public record RecurringJobResponse(
        UUID id,
        String name,
        String type,
        JsonNode payload,
        String cron,
        Instant nextRunAt,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public static RecurringJobResponse from(RecurringJob recurringJob) {
        return new RecurringJobResponse(
                recurringJob.getId(),
                recurringJob.getName(),
                recurringJob.getType(),
                recurringJob.getPayload(),
                recurringJob.getCron(),
                recurringJob.getNextRunAt(),
                recurringJob.isEnabled(),
                recurringJob.getCreatedAt(),
                recurringJob.getUpdatedAt());
    }
}
