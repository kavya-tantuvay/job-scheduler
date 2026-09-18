package com.jobscheduler.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background loops (poller, and later reaper and recurring scheduler).
 * {@code jobs.scheduling.enabled=false} disables them so tests can drive each loop by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "jobs.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
