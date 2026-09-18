package com.jobscheduler.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobscheduler.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * A cron-style definition. It never executes itself; on each due tick it enqueues a concrete
 * {@link com.jobscheduler.job.Job} and its {@code nextRunAt} is advanced from the cron expression.
 */
@Getter
@Entity
@Table(name = "recurring_jobs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecurringJob extends BaseEntity {

    /** Human-readable unique key, so the same schedule can't be defined twice. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    /** Spring cron format: second minute hour day-of-month month day-of-week. */
    @Column(nullable = false, length = 120)
    private String cron;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(nullable = false)
    private boolean enabled;

    public static RecurringJob create(String name, String type, JsonNode payload, String cron,
                                      Instant firstRunAt, boolean enabled) {
        RecurringJob recurringJob = new RecurringJob();
        recurringJob.name = Objects.requireNonNull(name, "name");
        recurringJob.type = Objects.requireNonNull(type, "type");
        recurringJob.payload = Objects.requireNonNull(payload, "payload");
        recurringJob.cron = Objects.requireNonNull(cron, "cron");
        recurringJob.nextRunAt = Objects.requireNonNull(firstRunAt, "firstRunAt");
        recurringJob.enabled = enabled;
        return recurringJob;
    }

    public void advanceTo(Instant nextRunAt) {
        this.nextRunAt = Objects.requireNonNull(nextRunAt, "nextRunAt");
    }

    public void pause() {
        this.enabled = false;
    }

    /** Re-enables the schedule from {@code nextRunAt}; fire times missed while paused are not made up. */
    public void resume(Instant nextRunAt) {
        this.enabled = true;
        advanceTo(nextRunAt);
    }
}
