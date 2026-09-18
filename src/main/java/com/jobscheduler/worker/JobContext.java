package com.jobscheduler.worker;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * What a handler gets to see about the job it is executing.
 *
 * @param attempt 1-based number of this execution attempt
 */
public record JobContext(UUID jobId, String type, JsonNode payload, int attempt, int maxAttempts) {
}
