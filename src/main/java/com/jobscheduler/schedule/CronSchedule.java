package com.jobscheduler.schedule;

import com.jobscheduler.common.exception.InvalidRequestException;
import com.jobscheduler.config.JobSchedulerProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Parses cron expressions and computes fire times in the configured zone. */
@Component
public class CronSchedule {

    private final ZoneId zone;

    public CronSchedule(JobSchedulerProperties properties) {
        this.zone = properties.recurring().zone();
    }

    /** @throws InvalidRequestException if the expression is malformed or can never fire */
    public void validate(String cron, Instant from) {
        nextAfter(cron, from);
    }

    /**
     * The first fire time strictly after {@code after}. Evaluated in the configured zone, so a
     * schedule like "every day at 02:00" follows that zone's daylight-saving changes.
     */
    public Instant nextAfter(String cron, Instant after) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Invalid cron expression '%s': %s".formatted(cron, e.getMessage()));
        }
        ZonedDateTime next = expression.next(after.atZone(zone));
        if (next == null) {
            throw new InvalidRequestException("Cron expression '%s' never fires".formatted(cron));
        }
        return next.toInstant();
    }
}
