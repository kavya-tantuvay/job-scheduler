package com.jobscheduler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All {@code jobs.*} settings, bound and validated at startup. */
@Validated
@ConfigurationProperties(prefix = "jobs")
public record JobSchedulerProperties(@Valid @DefaultValue Submission submission) {

    /**
     * @param defaultMaxAttempts attempts allowed when a submission doesn't specify one
     */
    public record Submission(@Min(1) @Max(20) @DefaultValue("3") int defaultMaxAttempts) {
    }
}
