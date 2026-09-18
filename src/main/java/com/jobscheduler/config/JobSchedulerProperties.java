package com.jobscheduler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/** All {@code jobs.*} settings, bound and validated at startup. */
@Validated
@ConfigurationProperties(prefix = "jobs")
public record JobSchedulerProperties(
        @Valid @DefaultValue Submission submission,
        @Valid @DefaultValue Worker worker,
        @Valid @DefaultValue Poller poller,
        @Valid @DefaultValue Retry retry,
        @Valid @DefaultValue Reaper reaper,
        @Valid @DefaultValue Recurring recurring,
        @Valid @DefaultValue RateLimit rateLimit) {

    /**
     * @param defaultMaxAttempts attempts allowed when a submission doesn't specify one
     */
    public record Submission(@Min(1) @Max(20) @DefaultValue("3") int defaultMaxAttempts) {
    }

    /**
     * @param poolSize      number of worker threads, i.e. jobs executing concurrently per instance
     * @param queueCapacity claimed jobs that may wait in memory for a free thread
     * @param instanceId    identifies this instance in {@code locked_by}; generated when blank
     * @param drainTimeout  on shutdown, how long to wait for in-flight jobs before interrupting them
     */
    public record Worker(
            @Min(1) @Max(256) @DefaultValue("8") int poolSize,
            @Min(0) @Max(1024) @DefaultValue("8") int queueCapacity,
            String instanceId,
            @NotNull @DefaultValue("30s") Duration drainTimeout) {
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

    /**
     * Recovery of jobs whose worker died mid-run.
     *
     * @param leaseTimeout how long a job may stay RUNNING before it is presumed abandoned. There
     *                     is no heartbeat, so this must be longer than the slowest job's runtime,
     *                     otherwise healthy long jobs get re-run.
     * @param interval     how often to look for expired leases
     * @param batchSize    maximum jobs recovered per statement
     */
    public record Reaper(
            @NotNull @DefaultValue("5m") Duration leaseTimeout,
            @NotNull @DefaultValue("30s") Duration interval,
            @Min(1) @Max(1000) @DefaultValue("100") int batchSize) {
    }

    /**
     * Cron-style recurring jobs.
     *
     * @param interval          how often due definitions are checked
     * @param zone              time zone cron expressions are evaluated in
     * @param misfirePolicy     what to do about fire times missed by more than {@code misfireThreshold}
     * @param misfireThreshold  lateness up to which a fire time counts as on time rather than missed
     * @param batchSize         maximum definitions fired per transaction
     */
    public record Recurring(
            @NotNull @DefaultValue("1s") Duration interval,
            @NotNull @DefaultValue("UTC") ZoneId zone,
            @NotNull @DefaultValue("FIRE_ONCE") MisfirePolicy misfirePolicy,
            @NotNull @DefaultValue("1m") Duration misfireThreshold,
            @Min(1) @Max(1000) @DefaultValue("100") int batchSize) {
    }

    public enum MisfirePolicy {
        /** Run once to make up for any number of missed fire times, then continue on schedule. */
        FIRE_ONCE,
        /** Drop missed fire times and simply continue on schedule. */
        SKIP
    }

    /**
     * Cluster-wide per-type throughput limits, enforced through Redis.
     *
     * @param enabled   requires Redis ({@code spring.data.redis.*}); when false no Redis is needed
     * @param perSecond maximum jobs of a type started per second across all instances, e.g.
     *                  {@code webhook: 5} to protect a downstream API; types not listed are unlimited
     */
    public record RateLimit(
            @DefaultValue("false") boolean enabled,
            Map<String, @Min(1) Integer> perSecond) {

        public RateLimit {
            perSecond = perSecond == null ? Map.of() : Map.copyOf(perSecond);
        }
    }
}
