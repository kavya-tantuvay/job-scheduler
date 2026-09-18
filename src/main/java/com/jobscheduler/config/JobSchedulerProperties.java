package com.jobscheduler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** All {@code jobs.*} settings, bound and validated at startup. */
@Validated
@ConfigurationProperties(prefix = "jobs")
public record JobSchedulerProperties(
        @Valid @DefaultValue Submission submission,
        @Valid @DefaultValue Worker worker,
        @Valid @DefaultValue Poller poller,
        @Valid @DefaultValue Retry retry) {

    /**
     * @param defaultMaxAttempts attempts allowed when a submission doesn't specify one
     */
    public record Submission(@Min(1) @Max(20) @DefaultValue("3") int defaultMaxAttempts) {
    }

    /**
     * @param poolSize      number of worker threads, i.e. jobs executing concurrently per instance
     * @param queueCapacity claimed jobs that may wait in memory for a free thread
     * @param instanceId    identifies this instance in {@code locked_by}; generated when blank
     */
    public record Worker(
            @Min(1) @Max(256) @DefaultValue("8") int poolSize,
            @Min(0) @Max(1024) @DefaultValue("8") int queueCapacity,
            String instanceId) {
    }

    /**
     * @param interval  delay between the end of one poll and the start of the next
     * @param batchSize maximum jobs claimed per claim query
     */
    public record Poller(
            @NotNull @DefaultValue("1s") Duration interval,
            @Min(1) @Max(500) @DefaultValue("10") int batchSize) {
    }

    /**
     * Exponential backoff between attempts: the ceiling for attempt n is
     * {@code min(maxDelay, baseDelay * 2^(n-1))}, and jitter picks a value in its upper half.
     */
    public record Retry(
            @NotNull @DefaultValue("2s") Duration baseDelay,
            @NotNull @DefaultValue("5m") Duration maxDelay) {
    }
}
